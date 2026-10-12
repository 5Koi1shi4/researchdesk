"""Supply generator nullability hints without changing the canonical OpenAPI 3.1 schema.

datamodel-code-generator 0.53 treats an omitted optional property as nullable unless
its legacy nullable hint is explicit. Pydantic skips validating omitted defaults,
so a non-nullable annotation with the generated None default permits omission but
rejects an explicitly supplied null. The authoritative 3.1 schema decides each hint.
"""
import json
import sys
from pathlib import Path

from jsonschema import Draft202012Validator

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
for schema in source["components"]["schemas"].values():
    required = schema.get("required", [])
    for name, definition in schema.get("properties", {}).items():
        if name not in required:
            validator = Draft202012Validator({**definition, "components": source["components"]})
            definition["nullable"] = validator.is_valid(None)
target = Path(sys.argv[2])
target.parent.mkdir(parents=True, exist_ok=True)
target.write_text(json.dumps(source, indent=2) + "\n", encoding="utf-8")
