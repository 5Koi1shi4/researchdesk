import json
from pathlib import Path
import pytest
from jsonschema import Draft202012Validator

CONTRACT = json.loads((Path(__file__).parents[3] / 'contracts/openapi.json').read_text(encoding='utf-8'))

@pytest.mark.parametrize('name', ['Report', 'ReportVersion', 'ReportExport', 'ReportSaveRequest', 'ReportCopyRequest', 'ReportExportRequest', 'ReportCreateRequest'])
def test_report_contract_is_namespaced_and_closed(name):
    assert name in CONTRACT['components']['schemas']
    assert CONTRACT['components']['schemas'][name]['additionalProperties'] is False

def test_export_contract_pins_positive_version_and_known_format():
    assert 'ReportExportRequest' in CONTRACT['components']['schemas']
    validator = Draft202012Validator({'$ref': '#/components/schemas/ReportExportRequest', **CONTRACT})
    assert validator.is_valid({'version': 1, 'format': 'PDF'})
    assert not validator.is_valid({'version': 0, 'format': 'PDF'})
    assert not validator.is_valid({'version': 1, 'format': 'HTML'})
    assert not validator.is_valid({'format': 'PDF'})

def test_report_version_reuses_canonical_citation():
    assert 'ReportVersion' in CONTRACT['components']['schemas']
    assert CONTRACT['components']['schemas']['ReportVersion']['properties']['citations']['items'] == {'$ref': '#/components/schemas/Citation'}
