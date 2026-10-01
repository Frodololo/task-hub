import subprocess, json

# Get all items
r = subprocess.run(["gh", "project", "item-list", "1", "--owner", "LibertoBaltasar", "--limit", "100", "--format", "json"], capture_output=True, text=True, timeout=30)
data = json.loads(r.stdout)
items = [i for i in data['items'] if '[Decisi' in i.get('title','')]
print(f"Found {len(items)} decision items")

# Status field ID from earlier gh project field-list output
field_id = "PVTSSF_lAHOCXo7m84BjLGtzhiAdBs"
# "Requiere decisión" option ID
option_id = "369de6d9"

for item in items:
    title = item['title']
    item_id = item['id']
    r = subprocess.run([
        "gh", "project", "item-edit",
        "--project-id", "1",
        "--id", item_id,
        "--field-id", field_id,
        "--single-select-option-id", option_id,
        "--owner", "LibertoBaltasar"
    ], capture_output=True, text=True, timeout=30)
    if r.returncode == 0:
        print(f"✅ {title}")
    else:
        err = r.stderr.strip()[:150]
        print(f"❌ {title}: {err}")