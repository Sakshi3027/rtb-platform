#!/usr/bin/env python3
"""
Trains CTR models on simulator data, scores them against the known ground truth, and exports the best one to
ONNX, together with a golden file of predictions that the Java bidder must reproduce exactly.

The ONNX model includes the one-hot encoder, so serving passes raw strings and never re-implements the encoding.

Usage: python ml/train_ctr.py --train data/train.csv --test data/test.csv
"""
import argparse
import json
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort
import pandas as pd
from skl2onnx import convert_sklearn
from skl2onnx.common.data_types import FloatTensorType, StringTensorType
from sklearn.compose import ColumnTransformer
from sklearn.ensemble import HistGradientBoostingClassifier
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import log_loss, roc_auc_score
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import OneHotEncoder
import onnx.helper

# Compatibility shim: skl2onnx's tree converter passes booleans in integer-list attributes, which current
# protobuf releases reject ("Expected an int, got a boolean"). In ONNX these flags are 0/1 integers, so
# converting them is exact; the ONNX-vs-scikit-learn parity check below verifies the exported model.
_original_make_attribute = onnx.helper.make_attribute


def _make_attribute_with_int_flags(key, value, *args, **kwargs):
    if isinstance(value, np.ndarray) and value.dtype == np.bool_:
        value = value.astype(np.int64)
    elif isinstance(value, (list, tuple)) and any(isinstance(v, (bool, np.bool_)) for v in value):
        value = [int(v) if isinstance(v, (bool, np.bool_)) else v for v in value]
    return _original_make_attribute(key, value, *args, **kwargs)


onnx.helper.make_attribute = _make_attribute_with_int_flags

CATEGORICAL = ["campaign_id", "size", "country", "category"]
SEGMENTS = ["sports-fan", "auto-intender", "traveler", "foodie", "gamer", "finance-pro"]
SEGMENT_COLUMNS = ["seg_" + segment.replace("-", "_") for segment in SEGMENTS]
NUMERIC = ["known_user"] + SEGMENT_COLUMNS
FEATURES = CATEGORICAL + NUMERIC
HIGH_VALUE_CTR = 0.04
GOLDEN_ROWS = 1_000
PARITY_TOLERANCE = 1e-5


def load(path):
    """Reads generator output and turns the pipe-separated segments into one 0/1 column per segment."""
    df = pd.read_csv(path, dtype=str, keep_default_na=False)
    df["known_user"] = df["known_user"].astype(np.float32)
    padded = "|" + df["segments"] + "|"
    for segment, column in zip(SEGMENTS, SEGMENT_COLUMNS):
        df[column] = padded.str.contains("|" + segment + "|", regex=False).astype(np.float32)
    df["true_ctr"] = df["true_ctr"].astype(float)
    df["click"] = df["click"].astype(int)
    return df


def pipeline(model):
    """One-hot encodes the string features, passes the 0/1 features through, then applies the model."""
    features = ColumnTransformer(
        [
            ("onehot", OneHotEncoder(handle_unknown="ignore", sparse_output=False), CATEGORICAL),
            ("flags", "passthrough", NUMERIC),
        ],
        remainder="drop",
        sparse_threshold=0.0,
    )
    return Pipeline([("features", features), ("model", model)])


def score(name, predictions, test, baseline_log_loss):
    y = test["click"].to_numpy()
    truth = test["true_ctr"].to_numpy()
    high_value = truth >= HIGH_VALUE_CTR
    loss = log_loss(y, predictions)
    return {
        "model": name,
        "log_loss": loss,
        "normalized_entropy": loss / baseline_log_loss,
        "auc": roc_auc_score(y, predictions) if np.std(predictions) > 0 else 0.5,
        "calibration": predictions.mean() / y.mean(),
        "high_value_calibration": predictions[high_value].mean() / truth[high_value].mean(),
        "mae_vs_truth": np.abs(predictions - truth).mean(),
    }


def print_table(results, oracle_loss, baseline_loss):
    print(f"\n{'model':<24}{'log loss':>10}{'NE':>8}{'gap closed':>12}{'AUC':>8}"
          f"{'calib':>8}{'high-value calib':>18}{'MAE vs truth':>14}")
    for r in results:
        gap = (baseline_loss - r["log_loss"]) / (baseline_loss - oracle_loss)
        r["gap_closed"] = gap
        print(f"{r['model']:<24}{r['log_loss']:>10.5f}{r['normalized_entropy']:>8.4f}{gap:>11.1%} {r['auc']:>8.4f}"
              f"{r['calibration']:>8.3f}{r['high_value_calibration']:>18.3f}{r['mae_vs_truth']:>14.6f}")


def print_reliability(name, predictions, test):
    """Groups predictions into ten equal-sized bins and compares each bin's average prediction with reality."""
    bins = pd.qcut(predictions, 10, labels=False, duplicates="drop")
    table = pd.DataFrame({"bin": bins, "predicted": predictions,
                          "observed": test["click"].to_numpy(), "true": test["true_ctr"].to_numpy()})
    summary = table.groupby("bin").agg(rows=("predicted", "size"), predicted=("predicted", "mean"),
                                       observed=("observed", "mean"), true=("true", "mean"))
    print(f"\nreliability of {name} (ten equal-sized bins, lowest predictions first):")
    print(f"{'rows':>8}{'predicted':>12}{'observed':>12}{'true':>12}")
    for _, row in summary.iterrows():
        print(f"{int(row['rows']):>8}{row['predicted']:>12.5f}{row['observed']:>12.5f}{row['true']:>12.5f}")


def export_onnx(model, path):
    """Converts the whole pipeline (encoder and classifier) to ONNX, with one named input per feature."""
    initial_types = ([(column, StringTensorType([None, 1])) for column in CATEGORICAL]
                     + [(column, FloatTensorType([None, 1])) for column in NUMERIC])
    onnx_model = convert_sklearn(
        model,
        initial_types=initial_types,
        options={id(model.named_steps["model"]): {"zipmap": False}},
        target_opset=17,
    )
    path.write_bytes(onnx_model.SerializeToString())


def onnx_predict(path, df):
    session = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
    inputs = {column: df[column].to_numpy(dtype=object).reshape(-1, 1) for column in CATEGORICAL}
    inputs.update({column: df[column].to_numpy(dtype=np.float32).reshape(-1, 1) for column in NUMERIC})
    return session.run(["probabilities"], inputs)[0][:, 1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--train", default="data/train.csv")
    parser.add_argument("--test", default="data/test.csv")
    parser.add_argument("--model-out", default="ml/artifacts/ctr-v1.onnx")
    parser.add_argument("--metrics-out", default="ml/artifacts/metrics.json")
    parser.add_argument("--golden-out", default="ml/golden/ctr_golden.csv")
    args = parser.parse_args()

    train, test = load(args.train), load(args.test)
    print(f"train {len(train):,} rows ({train['click'].sum():,} clicks) | test {len(test):,} rows "
          f"({test['click'].sum():,} clicks)")

    y_test = test["click"].to_numpy()
    base_rate = train["click"].mean()
    baseline_loss = log_loss(y_test, np.full(len(test), base_rate))
    oracle_loss = log_loss(y_test, test["true_ctr"].to_numpy())

    candidates = {
        "logistic regression": pipeline(LogisticRegression(C=1.0, max_iter=2000)),
        "gradient-boosted trees": pipeline(HistGradientBoostingClassifier(
            max_iter=300, learning_rate=0.1, max_leaf_nodes=31, l2_regularization=1.0,
            early_stopping=True, validation_fraction=0.1, n_iter_no_change=20, random_state=0)),
    }

    results = [
        score("oracle (true CTR)", test["true_ctr"].to_numpy(), test, baseline_loss),
        score("baseline (constant)", np.full(len(test), base_rate), test, baseline_loss),
    ]
    predictions = {}
    for name, model in candidates.items():
        started = time.time()
        model.fit(train[FEATURES], train["click"])
        predictions[name] = model.predict_proba(test[FEATURES])[:, 1]
        print(f"trained {name} in {time.time() - started:.1f}s")
        results.append(score(name, predictions[name], test, baseline_loss))

    print_table(results, oracle_loss, baseline_loss)

    best_name = min(candidates, key=lambda name: log_loss(y_test, predictions[name]))
    best = candidates[best_name]
    print_reliability(best_name, predictions[best_name], test)

    model_path = Path(args.model_out)
    model_path.parent.mkdir(parents=True, exist_ok=True)
    export_onnx(best, model_path)
    onnx_predictions = onnx_predict(model_path, test)
    max_diff = float(np.abs(onnx_predictions - predictions[best_name]).max())
    if max_diff > PARITY_TOLERANCE:
        raise SystemExit(f"ONNX predictions differ from scikit-learn by {max_diff}, above {PARITY_TOLERANCE}")
    print(f"\nexported {best_name} to {model_path} ({model_path.stat().st_size / 1024:.0f} KB); "
          f"ONNX vs scikit-learn max difference {max_diff:.2e}")

    golden = test.head(GOLDEN_ROWS)[["campaign_id", "size", "country", "category", "segments"]].copy()
    golden["known_user"] = test.head(GOLDEN_ROWS)["known_user"].astype(int)
    golden["expected_ctr"] = onnx_predictions[:GOLDEN_ROWS]
    golden_path = Path(args.golden_out)
    golden_path.parent.mkdir(parents=True, exist_ok=True)
    golden.to_csv(golden_path, index=False, float_format="%.9f")
    print(f"wrote {GOLDEN_ROWS:,} golden predictions to {golden_path}")

    metrics = {
        "train_rows": len(train), "test_rows": len(test),
        "oracle_log_loss": oracle_loss, "baseline_log_loss": baseline_loss,
        "selected_model": best_name, "onnx_max_abs_diff": max_diff,
        "results": [{key: (float(value) if isinstance(value, (float, np.floating)) else value)
                     for key, value in r.items()} for r in results],
    }
    Path(args.metrics_out).write_text(json.dumps(metrics, indent=2))
    print(f"wrote metrics to {args.metrics_out}")


if __name__ == "__main__":
    main()
