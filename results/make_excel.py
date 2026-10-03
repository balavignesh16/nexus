"""Builds NEXUS_Results_Obtained.xlsx from the real validation data already
captured in results/*.csv and results/validation_results.json - no values
are invented here, this only formats what was already measured."""
import json
import csv
from openpyxl import Workbook
from openpyxl.styles import Font, PatternFill, Alignment, Border, Side
from openpyxl.utils import get_column_letter
from openpyxl.drawing.image import Image as XLImage

ACCENT = "5B5BD6"
ACCENT_LIGHT = "EDEBFC"
PASS_GREEN = "1F9254"
INK = "16161F"
MUTED = "6B6B7A"

wb = Workbook()

def style_header(ws, row=1, ncols=1):
    for c in range(1, ncols + 1):
        cell = ws.cell(row=row, column=c)
        cell.font = Font(bold=True, color="FFFFFF", size=11, name="Calibri")
        cell.fill = PatternFill("solid", fgColor=ACCENT)
        cell.alignment = Alignment(vertical="center", wrap_text=True)
    ws.row_dimensions[row].height = 22

def autosize(ws, widths=None):
    if widths:
        for i, w in enumerate(widths, 1):
            ws.column_dimensions[get_column_letter(i)].width = w
        return
    for col in ws.columns:
        length = max((len(str(c.value)) if c.value is not None else 0) for c in col)
        ws.column_dimensions[col[0].column_letter].width = min(max(length + 3, 12), 60)

def write_rows(ws, header, rows, start_row=1):
    for j, h in enumerate(header, 1):
        ws.cell(row=start_row, column=j, value=h)
    style_header(ws, start_row, len(header))
    for i, row in enumerate(rows, start_row + 1):
        for j, val in enumerate(row, 1):
            cell = ws.cell(row=i, column=j, value=val)
            cell.alignment = Alignment(vertical="center", wrap_text=True)
            if i % 2 == 0:
                cell.fill = PatternFill("solid", fgColor="F7F7FB")

data = json.load(open("results/validation_results.json"))

# --- Sheet 1: Key Metrics ---
ws = wb.active
ws.title = "Key Metrics"
ws["A1"] = "NEXUS — RESULTS OBTAINED SO FAR"
ws["A1"].font = Font(bold=True, size=16, color=ACCENT)
ws["A2"] = "Validation run: grand_simulation.py, 230s, 2026-09-15 20:07:12–20:11:22 UTC"
ws["A2"].font = Font(italic=True, size=10, color=MUTED)
ws.merge_cells("A1:D1")
ws.merge_cells("A2:D2")

lat = data["command_dispatch_latency"]
metrics = [
    ("Backend tests", "709 / 709 passed", "mvnw.cmd test"),
    ("Frontend tests", "2265 / 2269 passed (4 pre-existing)", "npm run test -- run (vitest)"),
    ("Validation scenarios completed", "9 / 9", "grand_simulation.py console log"),
    ("Rule matches generated", "132 new (693→825)", "rule_matches table, before/after"),
    ("Commands executed & acknowledged", "142 new, 100% ACCEPTED", "action_history table, before/after"),
    ("Pipeline traces generated", "694 new", "pipeline_traces table, before/after"),
    ("Command dispatch latency (median / p95)", f"{lat['median_ms']}ms / {lat['p95_ms']}ms", "pipeline_traces, stage=ACTION, n=140"),
]
write_rows(ws, ["Metric", "Value", "Source"], metrics, start_row=4)
autosize(ws, [34, 34, 34])

# --- Sheet 2: Validation Table ---
ws2 = wb.create_sheet("Validation Table")
rows = []
with open("results/expected_vs_observed.csv", newline="", encoding="utf-8") as f:
    r = csv.reader(f)
    header = next(r)
    rows = list(r)
write_rows(ws2, ["Scenario", "Expected Behaviour", "Observed Behaviour", "Result"], rows)
autosize(ws2, [26, 42, 52, 10])
for row in ws2.iter_rows(min_row=2, max_row=ws2.max_row, min_col=4, max_col=4):
    for cell in row:
        if cell.value == "PASS":
            cell.font = Font(bold=True, color=PASS_GREEN)

# --- Sheet 3: Database Before/After ---
ws3 = wb.create_sheet("Database Before-After")
db = data["database_before_after"]
rows = [(k.replace("_", " ").title(), v["before"], v["after"], v["added"]) for k, v in db.items()]
write_rows(ws3, ["Entity", "Before", "After", "Added"], rows)
autosize(ws3, [24, 12, 12, 12])

# --- Sheet 4: Command + ACK Results ---
ws4 = wb.create_sheet("Command + ACK")
rows = []
with open("results/command_validation.csv", newline="", encoding="utf-8") as f:
    r = csv.reader(f)
    header = next(r)
    rows = list(r)
write_rows(ws4, header, rows)
autosize(ws4, [14, 14, 26, 12, 14, 14])

# --- Sheet 5: Multi-Capability & Isolation Result ---
ws5 = wb.create_sheet("Multi-Cap & Isolation")
mc = data["multi_capability_result"]
iso = data["multi_device_isolation_result"]
ws5["A1"] = "Multi-Capability Result (GAS + MOTION -> Buzzer + Fan + Relay Ch1)"
ws5["A1"].font = Font(bold=True, size=13, color=ACCENT)
ws5.merge_cells("A1:D1")
mc_rows = [
    ("GAS value", mc["input"]["gas_ppm"], "ppm, from live twin", mc["input"]["gas_evaluated_at"]),
    ("MOTION value", mc["input"]["motion"], "boolean, from live twin", mc["input"]["motion_evaluated_at"]),
]
write_rows(ws5, ["Signal", "Value", "Note", "Evaluated At (UTC)"], mc_rows, start_row=3)
r0 = 3 + len(mc_rows) + 2
ws5.cell(row=r0, column=1, value="Rules matched").font = Font(bold=True)
for i, rn in enumerate(mc["rules_matched"], r0 + 1):
    ws5.cell(row=i, column=1, value="• " + rn)
r1 = r0 + len(mc["rules_matched"]) + 2
act_rows = [(a["capability"], json.dumps(a["value"]), a["status"], a["ack_status"]) for a in mc["actions"]]
write_rows(ws5, ["Capability", "Command Value", "Status", "ACK"], act_rows, start_row=r1)

r2 = r1 + len(act_rows) + 3
ws5.cell(row=r2, column=1, value="Multi-Device Isolation Result").font = Font(bold=True, size=13, color=ACCENT)
ws5.merge_cells(f"A{r2}:D{r2}")
iso_rows = [
    ("Negative control", iso["negative_control"]["other_device"], f"{iso['negative_control']['match_count_before']} → {iso['negative_control']['match_count_after']}", iso["negative_control"]["result"]),
    ("Positive control", iso["positive_control"]["correct_device"], f"{iso['positive_control']['match_count_before']} → {iso['positive_control']['match_count_after']}", iso["positive_control"]["result"]),
]
write_rows(ws5, ["Test", "Device", "Match Count (before→after)", "Result"], iso_rows, start_row=r2 + 2)
autosize(ws5, [22, 26, 30, 40])

# --- Sheet 6: Restart / Persistence ---
ws6 = wb.create_sheet("Restart-Persistence")
rp = data["restart_persistence_result"]
rows = []
for k in rp["before_restart"]:
    rows.append((k.replace("_", " ").title(), rp["before_restart"][k], rp["after_restart"][k],
                 "PASS" if rp["before_restart"][k] == rp["after_restart"][k] else "FAIL"))
write_rows(ws6, ["Component", "Before Restart", "After Restart", "Result"], rows)
autosize(ws6, [22, 18, 18, 12])
for row in ws6.iter_rows(min_row=2, max_row=ws6.max_row, min_col=4, max_col=4):
    for cell in row:
        cell.font = Font(bold=True, color=PASS_GREEN if cell.value == "PASS" else "C0392B")

# --- Sheet 7: Command Dispatch Latency ---
ws7 = wb.create_sheet("Latency")
ws7["A1"] = "Command Dispatch Latency"
ws7["A1"].font = Font(bold=True, size=13, color=ACCENT)
ws7.merge_cells("A1:D1")
lat_rows = [
    ("Metric", "ACTION pipeline stage duration_ms"),
    ("Source", "pipeline_traces table, stage=ACTION, backend clock only"),
    ("Sample size (n)", lat["n"]),
    ("Median (p50)", f"{lat['median_ms']} ms"),
    ("p95", f"{lat['p95_ms']} ms"),
    ("Mean", f"{lat['mean_ms']} ms"),
    ("Max", f"{lat['max_ms']} ms"),
]
for i, (k, v) in enumerate(lat_rows, 3):
    ws7.cell(row=i, column=1, value=k).font = Font(bold=True)
    ws7.cell(row=i, column=2, value=v)
ws7.cell(row=11, column=1, value="Note").font = Font(bold=True)
ws7.cell(row=11, column=2, value=lat["distribution_note"])
ws7.cell(row=11, column=2).alignment = Alignment(wrap_text=True, vertical="top")
ws7.row_dimensions[11].height = 60
ws7.cell(row=13, column=1, value="Excluded metric").font = Font(bold=True, color="A8630A")
ws7.cell(row=13, column=2, value=lat["excluded_metric_note"])
ws7.cell(row=13, column=2).alignment = Alignment(wrap_text=True, vertical="top")
ws7.row_dimensions[13].height = 90
autosize(ws7, [18, 70])
try:
    img = XLImage("results/command_dispatch_latency.png")
    img.width, img.height = 560, 350
    ws7.add_image(img, "D3")
except Exception as e:
    print("Warning: could not embed latency chart image:", e)

wb.save("results/NEXUS_Results_Obtained.xlsx")
print("Saved results/NEXUS_Results_Obtained.xlsx")
