#!/usr/bin/env python3
"""Stdlib contract lint + example/schema/invariant checks, NOT a full OpenAPI validator.
Run: python3 docs/api/validate_contract.py
No HTTP, application startup, dependency installation or database access.
"""
import copy
import datetime as dt
import json
from pathlib import Path
import re
import uuid
from decimal import Decimal

HERE = Path(__file__).resolve().parent
DOC = json.loads((HERE / 'openapi.json').read_text())
FIXTURES = json.loads((HERE / 'examples.json').read_text())


def check(condition, message):
    if not condition:
        raise AssertionError(message)


def resolve(ref):
    check(ref.startswith('#/'), f'External reference forbidden: {ref}')
    value = DOC
    for key in ref[2:].split('/'):
        key = key.replace('~1', '/').replace('~0', '~')
        check(key in value, f'Unresolved reference: {ref}')
        value = value[key]
    return value


def walk(node):
    if isinstance(node, dict):
        if '$ref' in node:
            resolve(node['$ref'])
        for value in node.values():
            walk(value)
    elif isinstance(node, list):
        for item in node:
            walk(item)


def valid_type(value, kind):
    return {
        'null': value is None,
        'string': isinstance(value, str),
        'boolean': isinstance(value, bool),
        'integer': isinstance(value, int) and not isinstance(value, bool),
        'number': isinstance(value, (int, float)) and not isinstance(value, bool),
        'object': isinstance(value, dict),
        'array': isinstance(value, list),
    }.get(kind, False)


def validate(value, schema, at='$'):
    if '$ref' in schema:
        return validate(value, resolve(schema['$ref']), at)
    for keyword in ('oneOf', 'anyOf'):
        if keyword in schema:
            matches = 0
            for choice in schema[keyword]:
                try:
                    validate(value, choice, at)
                    matches += 1
                except AssertionError:
                    pass
            check(matches == 1 if keyword == 'oneOf' else matches > 0,
                  f'{at}: {keyword} matched {matches} alternatives')
    if 'type' in schema:
        kinds = schema['type'] if isinstance(schema['type'], list) else [schema['type']]
        check(any(valid_type(value, kind) for kind in kinds), f'{at}: expected {kinds}')
    if 'enum' in schema:
        check(value in schema['enum'], f'{at}: enum')
    if 'const' in schema:
        check(value == schema['const'], f'{at}: const')
    if isinstance(value, dict):
        props = schema.get('properties', {})
        check(all(key in value for key in schema.get('required', [])), f'{at}: missing required property')
        if schema.get('additionalProperties') is False:
            check(set(value) <= set(props), f'{at}: unexpected properties {set(value)-set(props)}')
        for key, item in value.items():
            if key in props:
                validate(item, props[key], f'{at}.{key}')
    if isinstance(value, list):
        check(len(value) >= schema.get('minItems', 0), f'{at}: minItems')
        check(len(value) <= schema.get('maxItems', len(value)), f'{at}: maxItems')
        for index, item in enumerate(value):
            validate(item, schema.get('items', {}), f'{at}[{index}]')
    if isinstance(value, str):
        check(len(value) >= schema.get('minLength', 0), f'{at}: minLength')
        check(len(value) <= schema.get('maxLength', len(value)), f'{at}: maxLength')
        if 'pattern' in schema:
            check(re.search(schema['pattern'], value) is not None, f'{at}: pattern')
        fmt = schema.get('format')
        if fmt == 'date':
            check(re.fullmatch(r'\d{4}-\d{2}-\d{2}', value) is not None, f'{at}: date spelling')
            try:
                dt.date.fromisoformat(value)
            except ValueError as exc:
                raise AssertionError(f'{at}: invalid calendar date') from exc
        elif fmt == 'date-time':
            try:
                instant = dt.datetime.fromisoformat(value.replace('Z', '+00:00'))
                check(instant.tzinfo is not None, f'{at}: time zone required')
            except ValueError as exc:
                raise AssertionError(f'{at}: invalid instant') from exc
        elif fmt == 'uuid':
            try:
                uuid.UUID(value)
            except ValueError as exc:
                raise AssertionError(f'{at}: invalid UUID') from exc
        elif fmt == 'email':
            check(re.fullmatch(r'[^\s@]+@[^\s@]+\.[^\s@]+', value) is not None, f'{at}: fixture email')
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        check(value >= schema.get('minimum', value), f'{at}: minimum')
        check(value <= schema.get('maximum', value), f'{at}: maximum')


def expect_invalid(value, schema_name):
    try:
        validate(value, resolve('#/components/schemas/' + schema_name))
    except AssertionError:
        return
    raise AssertionError(f'Negative fixture unexpectedly accepted: {schema_name}')


check(DOC['openapi'] == '3.1.0', 'OpenAPI version')
check(DOC['x-contract-status'] in ('target-not-implemented', 'local-implementation-with-registration-gate'), 'Recognized contract stage')
check(len(DOC['servers']) == 1 and DOC['servers'][0]['url'] == '/api/v2', 'Browser API base path')
walk(DOC)
ids = set()
operations = 0
for path, item in DOC['paths'].items():
    check(not path.startswith('/api/'), f'Duplicated server prefix: {path}')
    path_vars = set(re.findall(r'\{([^}]+)\}', path))
    for method, operation in item.items():
        check(method in ('get', 'post', 'put', 'delete', 'patch'), f'Unsupported method {method}')
        operations += 1
        opid = operation['operationId']
        check(opid not in ids, f'Duplicate operationId {opid}')
        ids.add(opid)
        check(operation.get('x-implementation-status') in ('planned-not-implemented', 'implemented-locally'), opid)
        parameters = [resolve(p['$ref']) if '$ref' in p else p for p in operation.get('parameters', [])]
        check(len({(p['name'], p['in']) for p in parameters}) == len(parameters), f'{opid}: duplicate parameter')
        check({p['name'] for p in parameters if p['in'] == 'path'} == path_vars, f'{opid}: path params')
        check(all(p.get('required') is True for p in parameters if p['in'] == 'path'), f'{opid}: path required')
        for requirement in operation.get('security', DOC['security']):
            check(set(requirement) <= set(DOC['components']['securitySchemes']), f'{opid}: security scheme')
        if method == 'post':
            check(any('csrf' in req for req in operation['security']), f'{opid}: unsafe operation without CSRF')
        if path.startswith(('/me/', '/admin/', '/bookings')):
            check(any('session' in req for req in operation['security']), f'{opid}: missing session')
        if path.startswith('/admin/'):
            check(operation.get('x-required-roles') == ['ADMIN'], f'{opid}: role scope')
        for status, resp in operation['responses'].items():
            check(re.fullmatch(r'[1-5][0-9]{2}', status) is not None, f'{opid}: response status')
            resp = resolve(resp['$ref']) if '$ref' in resp else resp
            check('Cache-Control' in resp['headers'] and 'X-Trace-Id' in resp['headers'], f'{opid}: response policy')
            for media in resp.get('content', {}).values():
                for ex in media.get('examples', {}).values():
                    ex = resolve(ex['$ref']) if '$ref' in ex else ex
                    validate(ex['value'], media['schema'], f'{opid} response {status}')

fixture_map = {}
for fixture in FIXTURES['examples']:
    name = fixture['name']
    check(name not in fixture_map, f'Duplicate fixture {name}')
    fixture_map[name] = fixture['value']
    validate(fixture['value'], resolve(fixture['schema']), name)
    check(DOC['components']['examples'][name]['value'] == fixture['value'], f'Drifted embedded fixture {name}')
    if fixture['schema'].endswith('/ApiError'):
        check(fixture['value']['status'] in (400, 403, 404, 409), f'{name}: unexpected example status')

for name in ('quote', 'booking', 'receipt'):
    item = fixture_map[name]
    pricing = item['pricing']
    check(Decimal(pricing['nightlyPrice']) * pricing['nights'] == Decimal(pricing['totalPrice']), f'{name}: money arithmetic')
    stay = item.get('stay', item)
    check((dt.date.fromisoformat(stay['checkOutDate']) - dt.date.fromisoformat(stay['checkInDate'])).days == pricing['nights'], f'{name}: nights')
    if 'snapshot' in item:
        from zoneinfo import ZoneInfo
        deadline = dt.datetime.combine(dt.date.fromisoformat(stay['checkInDate']), dt.time(), ZoneInfo(item['snapshot']['timeZone'])).astimezone(dt.timezone.utc)
        saved = item.get('cancellationDeadlineAt', item.get('cancellation', {}).get('deadlineAt'))
        check(deadline == dt.datetime.fromisoformat(saved.replace('Z', '+00:00')), f'{name}: hotel-local cancellation instant')
legacy = fixture_map['legacyUnknown']
check(all(legacy[field] is None for field in ('pricing', 'snapshot', 'guests', 'createdAt')), 'Legacy facts invented')
check(legacy['cancellation']['canCancel'] is False and legacy['cancellation']['deadlineAt'] is None, 'Unknown legacy TZ policy')
for schema in ('HotelCard', 'HotelDetail', 'Room', 'City', 'Photo'):
    properties = DOC['components']['schemas'][schema]['properties']
    check(not (set(properties) & {'userId', 'email', 'password', 'bookings', 'unavailableDates'}), f'{schema}: public PII')
expect_invalid(10.50, 'Money')
expect_invalid('10.5', 'Money')
expect_invalid('1e3', 'Money')
expect_invalid({**fixture_map['create'], 'userId': '99'}, 'CreateBookingRequest')
expect_invalid({**fixture_map['create'], 'quoteToken': 'bad'}, 'CreateBookingRequest')
expect_invalid({**fixture_map['create'], 'guests': 0}, 'CreateBookingRequest')
expect_invalid({**fixture_map['legacyUnknown'], 'pricing': fixture_map['booking']['pricing']}, 'Booking')
expect_invalid({**fixture_map['booking'], 'snapshot': None}, 'Booking')
expect_invalid('2026-02-30', 'LocalDate')
CASES = json.loads((HERE / 'acceptance-cases.json').read_text())
check(CASES['status'] == 'requirements-not-executed', 'Acceptance execution claim')
case_ids = set()
known_codes = set(DOC['components']['schemas']['ApiError']['properties']['code']['enum'])
for case in CASES['cases']:
    check(case['id'] not in case_ids, 'Duplicate acceptance id')
    case_ids.add(case['id'])
    check(set(case['operationIds']) <= ids, f"{case['id']}: missing operationId")
    check(set(case['expectedCodes']) <= known_codes, f"{case['id']}: unknown error code")
    check(case['status'] == 'requirement-not-executed', 'No execution proof')
    check(all(100 <= status <= 599 for status in case['expectedHttp']), 'Invalid acceptance HTTP status')
check('AC-48' in case_ids, 'Delayed-worker regression missing')
check('/me/booking-operations/result' in DOC['paths'], 'Recovery lookup missing')
check('/me/booking-operations/fence' in DOC['paths'], 'Late-request fence missing')
check(DOC['paths']['/bookings']['post']['requestBody']['required'] is False, 'Header-only successful replay must be expressible')
check('quoteToken' in DOC['components']['schemas']['CreateBookingRequest']['required'], 'Fresh create must bind quote')
check('quoteToken' not in DOC['components']['schemas']['BookingCreationReceipt']['properties'], 'Receipt must not require expired token')
print(f'PASS: {len(case_ids)} future acceptance requirements reference valid operations/codes; bodyless replay and recovery/fence contract present.')
print(f'PASS: {len(DOC["paths"])} paths, {operations} unique operations, {len(DOC["components"]["schemas"])} schemas; all local refs/security/path params checked.')
print(f'PASS: {len(fixture_map)} example fixtures + embedded response examples; decimal/date/deadline/privacy invariants and 9 negative checks.')
print('Scope: structural and supported-schema validation only; no full third-party OpenAPI meta-schema validation, backend or runtime tests.')
