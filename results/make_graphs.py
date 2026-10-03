"""Generates the two evidence graphs from real, already-queried validation
data (results/activity_by_minute.csv and results/persisted_entities.csv) -
no synthetic points, every value comes from an actual DB query against the
Grand Simulation validation run."""
import csv
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

# --- Graph 1: system activity over time ---
rows = []
with open("results/activity_by_minute.csv") as f:
    for line in f:
        line = line.strip()
        if not line:
            continue
        minute, series, count = line.split("|")
        rows.append((minute.replace("+00", ""), series, int(count)))

minutes = sorted(set(r[0] for r in rows))
series_names = ["telemetry", "events", "rule_matches", "actions"]
data = {s: [0] * len(minutes) for s in series_names}
for minute, series, count in rows:
    if series in data:
        data[series][minutes.index(minute)] = count

labels = [m.split(" ")[1][:5] for m in minutes]  # HH:MM

fig, ax = plt.subplots(figsize=(9, 5))
colors = {"telemetry": "#4C72B0", "events": "#DD8452", "rule_matches": "#55A868", "actions": "#C44E52"}
for s in series_names:
    ax.plot(labels, data[s], marker="o", label=s.replace("_", " ").title(), color=colors[s])
ax.set_xlabel("Time (UTC, HH:MM)")
ax.set_ylabel("Records per minute")
ax.set_title("NEXUS System Activity Over Time\n(Grand Simulation validation run, real DB counts)")
ax.legend()
ax.grid(True, alpha=0.3)
fig.tight_layout()
fig.savefig("results/system_activity_over_time.png", dpi=150)
print("Saved results/system_activity_over_time.png")

# --- Graph 2: persisted operational data by entity (deltas from validation run) ---
entities = []
with open("results/persisted_entities.csv") as f:
    reader = csv.reader(f)
    next(reader)
    for row in reader:
        entities.append((row[0], int(row[3])))  # name, added

fig2, ax2 = plt.subplots(figsize=(8, 5))
names = [e[0] for e in entities]
values = [e[1] for e in entities]
bars = ax2.bar(names, values, color="#4C72B0")
ax2.set_ylabel("Records added during validation run")
ax2.set_title("Persisted Operational Data by Entity\n(Delta: after validation run - before)")
ax2.bar_label(bars)
plt.xticks(rotation=20, ha="right")
fig2.tight_layout()
fig2.savefig("results/persisted_entities.png", dpi=150)
print("Saved results/persisted_entities.png")

# --- Graph 3: command dispatch latency (ACTION pipeline stage duration) ---
# NOTE: action_history.ack_received_at is derived from the simulated
# device's own whole-second-truncated ACK payload timestamp, not the
# backend's receipt time - comparing it against the millisecond-precision
# dispatch timestamp produces meaningless (often negative) deltas, so it is
# deliberately NOT used here. pipeline_traces.duration_ms for the ACTION
# stage is measured entirely on the backend's own clock and is a real,
# defensible latency: the processing time to validate and dispatch a
# command, not the full round-trip to the device's ACK.
durations = []
with open("results/action_stage_durations.csv") as f:
    for line in f:
        line = line.strip()
        if line:
            durations.append(int(line))

durations.sort()
p50 = durations[len(durations) // 2]
p95 = durations[int(len(durations) * 0.95)]

fig3, ax3 = plt.subplots(figsize=(8, 5))
bins = range(0, max(durations) + 5, 5)
ax3.hist(durations, bins=bins, color="#4C72B0", edgecolor="white", linewidth=0.5)
ax3.axvline(p50, color="#55A868", linestyle="--", linewidth=1.5, label=f"Median (p50): {p50}ms")
ax3.axvline(p95, color="#C44E52", linestyle="--", linewidth=1.5, label=f"p95: {p95}ms")
ax3.set_xlabel("ACTION stage duration (ms)")
ax3.set_ylabel("Number of commands")
ax3.set_title(f"Command Dispatch Latency\n(ACTION pipeline stage, n={len(durations)}, backend clock only)")
ax3.legend()
ax3.grid(True, alpha=0.3, axis="y")
fig3.tight_layout()
fig3.savefig("results/command_dispatch_latency.png", dpi=150)
print("Saved results/command_dispatch_latency.png")
