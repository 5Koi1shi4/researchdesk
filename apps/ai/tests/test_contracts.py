import json
from pathlib import Path

import pytest
from jsonschema import Draft202012Validator
from pydantic import ValidationError
from generated import contracts

CONTRACT = json.loads((Path(__file__).parents[3] / "contracts/openapi.json").read_text())
EXAMPLES = json.loads((Path(__file__).parents[3] / "contracts/examples.json").read_text())


def test_optional_conversation_id_can_be_omitted_but_cannot_be_null():
    omitted = EXAMPLES["GenerationRequest"][0]
    assert contracts.GenerationRequest.model_validate(omitted).model_dump(mode="json", exclude_unset=True) == omitted
    explicit_null = {**omitted, "conversationId": None}
    validator = Draft202012Validator({"$ref": "#/components/schemas/GenerationRequest", **CONTRACT})
    assert not validator.is_valid(explicit_null)
    assert not Draft202012Validator(contracts.GenerationRequest.model_json_schema()).is_valid(explicit_null)
    with pytest.raises(ValidationError):
        contracts.GenerationRequest.model_validate(explicit_null)


@pytest.mark.parametrize("name", ["DocumentState", "TaskState", "RunState", "RunKind"])
def test_schema_rejects_unknown_enum(name):
    validator = Draft202012Validator({"$ref": f"#/components/schemas/{name}", **CONTRACT})
    assert not validator.is_valid("UNKNOWN")


@pytest.mark.parametrize("name", ["DocumentState", "TaskState", "RunState", "RunKind", "Availability"])
def test_generated_python_rejects_unknown_enum(name):
    with pytest.raises(ValidationError):
        getattr(contracts, name).model_validate("UNKNOWN")


@pytest.mark.parametrize("name,examples", EXAMPLES.items())
def test_shared_fixtures_match_schema_and_generated_python(name, examples):
    validator = Draft202012Validator({"$ref": f"#/components/schemas/{name}", **CONTRACT})
    for example in examples:
        validator.validate(example)
        parsed = getattr(contracts, name).model_validate(example)
        assert parsed.model_dump(mode="json", exclude_unset=True) == example


@pytest.mark.parametrize("event", [
    {"runId": "r1", "seq": 1, "type": "UNKNOWN", "payload": {}},
    {"runId": "r1", "seq": 1, "type": "delta", "payload": {"state": "COMPLETED"}},
    {"runId": "r1", "seq": 1, "type": "completed", "payload": {"state": "SUCCEEDED"}},
])
def test_event_kind_and_payload_cannot_disagree(event):
    validator = Draft202012Validator({"$ref": "#/components/schemas/RunEvent", **CONTRACT})
    assert not validator.is_valid(event)
    with pytest.raises(ValidationError):
        contracts.RunEvent.model_validate(event)
