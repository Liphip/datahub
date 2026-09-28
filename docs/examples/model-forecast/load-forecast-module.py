"""Load forecast for a substation: fit a line to a day's hourly readings, or apply the
saved line to one new reading.

A PROCESSING module runs once per record, so this single module has two roles and decides
which one to play from the shape of the record:

  - a record with a ``readings`` list is a training batch: fit a straight line through the
    hourly readings by ordinary least squares and save it with ``save_model``
  - any other record is a single new reading: load the saved line with ``load_model`` and
    forecast the next hour

One module rather than two because a model artifact is module-scoped: a module can only
load a model that the same module saved. See the README for what that means and what
would have to change to split training and inference into separate modules.
"""

MODEL_NAME = "load-forecast"


def _fit(readings):
    """Ordinary least squares through (hour, loadKwh) points; no third-party libraries."""
    xs = [float(r["hour"]) for r in readings]
    ys = [float(r["loadKwh"]) for r in readings]
    if len(xs) < 2:
        raise ValueError("need at least two readings to fit a line")
    n = len(xs)
    mean_x = sum(xs) / n
    mean_y = sum(ys) / n
    denominator = sum((x - mean_x) ** 2 for x in xs)
    if denominator == 0:
        raise ValueError("every reading reports the same hour")
    slope = sum((x - mean_x) * (y - mean_y) for x, y in zip(xs, ys)) / denominator
    return {
        "kind": "linear",
        "slope": slope,
        "intercept": mean_y - slope * mean_x,
        "observations": n,
        "meanLoadKwh": mean_y,
    }


def process(input_data, query, save_model, load_model):
    if input_data.get("readings"):
        model = _fit(input_data["readings"])
        save_model(MODEL_NAME, model)
        return {
            "role": "train",
            "station": input_data.get("station"),
            "date": input_data.get("date"),
            "observations": model["observations"],
            "slope": model["slope"],
            "intercept": model["intercept"],
        }

    model = load_model(MODEL_NAME)
    if model is None:
        # Nothing trained yet. Report it rather than fail: an inference pipeline that starts
        # before the first nightly run should not put records on the dead-letter queue.
        return {
            "role": "infer",
            "station": input_data.get("station"),
            "hour": input_data.get("hour"),
            "modelState": "missing",
        }

    hour = float(input_data["hour"])
    return {
        "role": "infer",
        "station": input_data.get("station"),
        "hour": hour,
        "loadKwh": input_data.get("loadKwh"),
        "forecastNextHourKwh": round(model["slope"] * hour + model["intercept"], 2),
        "modelState": "applied",
    }
