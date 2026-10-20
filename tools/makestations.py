# 20/10/2026 created by Claude Opus 5.5
# 20/10/2026 last modified by Claude Opus 5.5
# makestations.py
# Builds app/src/main/assets/stations.csv from BoM's station list (stations.txt, product IDCJMC0014): one row
# per open station with a WMO number, as "bomId,wmo,state,lat,lon", so the app can find the observation feed.
# Usage: python3 makestations.py stations.txt output.csv
import sys

# States whose observation feeds the app knows (ACT stations use the NSW feed).
STATES = {"NSW", "ACT", "VIC", "QLD", "SA", "WA", "TAS", "NT"}


# Parses one station line from the right, as names contain spaces; returns a CSV row or "" if not wanted.
def stationRow(line):
  parts = line.split()

  if len(parts) < 10 or not parts[0].isdigit() or not parts[-1].isdigit() or parts[-8] != "..":
    return ""
  lat = float(parts[-7])
  lon = float(parts[-6])
  state = parts[-4]
  if state not in STATES or not (-45 < lat < -9 and 110 < lon < 160):
    return ""
  return parts[0] + "," + parts[-1] + "," + state + "," + parts[-7] + "," + parts[-6]


rows = [r for r in (stationRow(l) for l in open(sys.argv[1], encoding="latin-1")) if r]
open(sys.argv[2], "w").write("\n".join(rows) + "\n")
print(str(len(rows)) + " stations written")
