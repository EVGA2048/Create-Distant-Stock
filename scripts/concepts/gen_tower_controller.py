#!/usr/bin/env python3
"""Offline one-cell signal tower controller study; never writes game resources.

build(state='bound') -> render_scene mesh; textures() -> namespaced PIL textures.
Default: generate deterministic mesh, contract and all previews. --check: read-only.
Uses existing Pillow/NumPy and Create jar via create_materials; installs nothing.
"""
from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path

sys.dont_write_bytecode = True
import numpy as np
from PIL import Image, ImageDraw, ImageEnhance
from create_materials import MATERIALS, material, source
from render_scene import BG, MUTED, box, ring, render, rotation, text, header

ROOT = Path(__file__).resolve().parents[2]
DOC = ROOT / 'docs/design/concepts/tower-controller'
OUT = ROOT / 'build/art/concepts'
PREFIX = 'tower_controller/'
STATES = {
    'unbound': {'color': '#85939B', 'label': 'UNBOUND', 'meaning': 'No tower identity; controls unavailable'},
    'bound': {'color': '#63BBD0', 'label': 'BOUND', 'meaning': 'Binding valid; not a chunk-loading success lamp'},
    'out_of_range': {'color': '#D5AD69', 'label': 'OUT OF RANGE', 'meaning': 'Binding retained; configuration unavailable'},
    'fault': {'color': '#CA7065', 'label': 'FAULT', 'meaning': 'Identity, structure, permission, load or apply failure'},
}
STRUCTURAL = ('casing', 'andesite', 'cast_white', 'blue_gray', 'iron')
BOUNDS = [[-8, 0, -8], [8, 16, 8]]


def textures():
    """All material keys are prefixed to safely merge with tower/dock textures."""
    result = {PREFIX + name: material(name) for name in STRUCTURAL}
    result[PREFIX + 'dark_iron'] = ImageEnhance.Brightness(material('iron')).enhance(.65)
    # Only the tiny physical lens and painted knob pointer are original accents.
    result[PREFIX + 'pointer'] = Image.new('RGBA', (16, 16), '#CAE9EB')
    for state, info in STATES.items():
        lens = Image.new('RGBA', (16, 16), info['color'])
        draw = ImageDraw.Draw(lens)
        draw.rectangle((2, 1, 4, 14), fill='#E1F1F3')
        lens.putalpha(125 if state != 'unbound' else 80)
        result[PREFIX + 'lens_' + state] = lens
    return result


def build(state='bound'):
    """Return standalone north-facing mesh, x/z -8..8, y 0..16, no tower parts."""
    if state not in STATES:
        raise ValueError(f'Unknown controller state: {state!r}')
    mesh = []

    def b(lo, hi, mat, group, full_uv=False):
        faces = box(lo, hi, PREFIX + mat, group='tower_controller/' + group)
        if full_uv:
            for f in faces:
                f.update(uv=[[0, 0], [16, 0], [16, 16], [0, 16]], clamp=True)
        mesh.extend(faces)

    # Intact Create casing side panels under cast stone caps; short recessed fascia.
    b((-7.6, 1.4, -6.3), (7.6, 14.6, 7.6), 'casing', 'casing', True)
    b((-8, 0, -8), (8, 1.4, 8), 'andesite', 'base')
    b((-8, 14.6, -8), (8, 16, 8), 'cast_white', 'lid')
    for x in (-8, 6.6):
        b((x, 1.4, -7.2), (x + 1.4, 14.6, -6.1), 'cast_white', 'front_jamb')
    b((-6.5, 3.4, -6.85), (6.5, 13.3, -6.25), 'blue_gray', 'fascia', True)
    b((-5.4, 10.3, -7.05), (1.3, 12.5, -6.8), 'cast_white', 'short_panel')
    # Short cast label plate, engraved index marks, not an electronic display.
    for x in (-4.4, -2.7, -1):
        b((x, 11, -7.13), (x + .6, 11.8, -7.03), 'dark_iron', 'index')
    mesh.extend(ring((-2.5, 6.8, -7.2), 2.65, .7, 1.2,
                     PREFIX + 'iron', segments=12, group=PREFIX + 'knob_rim'))
    b((-3.9, 5.4, -7.65), (-1.1, 8.2, -6.95), 'blue_gray', 'knob_hub')
    b((-4.25, 6.35, -8), (-.75, 7.25, -7.6), 'iron', 'knob_grip')
    b((-2.7, 7.1, -7.78), (-2.3, 8.2, -7.66), 'pointer', 'knob_pointer')
    # Lens sits over a mechanical backing, so transparency is visible.
    b((2.3, 9.5, -7.45), (5.7, 13.1, -6.85), 'iron', 'lamp_bezel')
    b((2.85, 10, -7.54), (5.15, 12.6, -7.44), 'cast_white', 'lamp_backing')
    b((3, 10.15, -7.98), (5, 12.45, -7.55), 'lens_' + state, 'binding_lamp')
    # A small mechanical lever; position remains fixed across binding studies.
    b((3, 5.1, -7.2), (5, 8.1, -6.8), 'iron', 'lever_socket')
    b((3.65, 5.6, -8), (4.35, 7.6, -7.15), 'cast_white', 'lever')
    for x in (-5.9, 5.1):
        for y in (4, 12.2):
            b((x, y, -7.05), (x + .7, y + .7, -6.86), 'iron', 'fasteners')
    # Rear access cover and hinges leave the recognizable casing border exposed.
    b((-4.8, 4, 7.6), (4.8, 11.8, 7.8), 'andesite', 'rear_cover', True)
    b((-1.8, 7.2, 7.8), (1.8, 8.7, 8), 'blue_gray', 'rear_latch')
    for y in (5, 10):
        b((-5.5, y, 7.6), (-3.8, y + .8, 8), 'iron', 'rear_hinges')
    # Small side retaining shoes rather than an extra device or data port.
    for x in (-8, 7.6):
        for y in (3.1, 12.1):
            b((x, y, -2), (x + .4, y + .8, 2), 'iron', 'side_retainer')
    for f in mesh:
        f.update(kind='placed_controller_concept', owner_cell=[0, 0, 0])
    return mesh


def inspector_build():
    """Handheld inspection-tool mesh only; no placed block or runtime action."""
    mesh = []

    def b(lo, hi, mat, group):
        mesh.extend(box(lo, hi, PREFIX + mat, group=PREFIX + 'inspector/' + group))

    b((-1.6, 0, -1.3), (1.6, 6.5, 1.3), 'blue_gray', 'handle')
    b((-1.9, .4, -1.5), (1.9, 1.5, 1.5), 'iron', 'handle_cap')
    b((-4.6, 5.8, -1.5), (4.6, 14.8, 1.5), 'casing', 'body')
    b((-5, 14, -1.7), (5, 16, 1.7), 'cast_white', 'cap')
    b((-5, 5.6, -1.7), (5, 7, 1.7), 'andesite', 'lower_band')
    b((-4.1, 8, -1.85), (4.1, 13.8, -1.5), 'blue_gray', 'faceplate')
    mesh.extend(ring((0, 10.9, -2.1), 2.45, .6, .8, PREFIX + 'iron',
                     segments=12, group=PREFIX + 'inspector/dial'))
    b((-1.35, 9.6, -2.25), (1.35, 12.2, -1.9), 'cast_white', 'dial_backing')
    b((-.18, 10.2, -2.65), (.18, 12, -2.3), 'dark_iron', 'needle')
    b((2.9, 12.6, -2), (3.7, 13.4, -1.85), 'pointer', 'mark')
    for f in mesh:
        f.update(kind='handheld_inspector_concept')
    return mesh


def inspector_concept():
    return {
        'schemaVersion': 1, 'status': 'offline_concept_only',
        'name': {'zh_cn': '区块加载器检查器', 'en_us': 'Chunk Loader Inspector'},
        'suggestedRegistryId': 'distantstock:chunk_loader_inspector',
        'registered': False, 'kind': 'handheld_item', 'placedBlock': False,
        'preview': 'build/art/concepts/tower-controller-inspector.png',
        'requiredTowerStructure': False, 'readOnly': True,
        'appearance': 'Create casing mechanical dial with short blue-gray handle; no electronic terminal',
        'mesh': 'models/inspector.mesh.json', 'meshFormat': 'render_scene, not game item JSON',
        'meshApi': 'inspector_build() -> list[face]; reuse textures()',
        'contract': 'inspector-contract.json', 'simulatedSnapshot': 'inspector-snapshot.json',
        'interactionProposal': 'Inspect a server-validated tower or a controller reference; view server snapshot only',
        'mayLoadChunks': False, 'mayModifyConfig': False, 'mayManageTickets': False,
        'gameGuiImplemented': False, 'serverImplemented': False,
    }


def inspector_contract():
    return {
        'schemaVersion': 1, 'status': 'proposal_not_implemented',
        'item': 'distantstock:chunk_loader_inspector', 'readOnly': True,
        'operation': {
            'name': 'inspect', 'transport': None,
            'request': ['hand', 'targetReference', 'expectedTowerIdentity (if previously inspected)'],
            'targetReference': 'tower base position or controller position; candidate only, server resolves actual identity',
            'serverChecks': ['actor holds actual inspector item', 'actor inspection permission', 'same dimension',
                             'server-defined inspection reach', 'target already loaded before world reads',
                             'resolve current tower UUID + dimension + basePos', 'tower completeness/availability',
                             'expected identity matches when supplied'],
            'onIdentityMismatch': 'TOWER_REPLACED; never reuse old snapshot for a new tower at the same position',
            'onDenied': 'no location/config/ticket data disclosed',
            'allowedSideEffects': ['audit logging and read-request rate limiting only'],
            'forbiddenSideEffects': ['load or generate chunks', 'acquire, renew or release tickets',
                                     'change selection or loading switch', 'change tower configuration/revision',
                                     'bind or rebind controller', 'start logistics or scan unloaded world'],
            'dataSources': 'existing server tower config, tower-owned ticket ledger and non-loading chunk observations',
            'polling': 'explicit refresh or bounded server-rate-limited read; interval TBD; no background force loading',
        },
        'rangeDistinctions': {
            'configurationReach': 'controller-to-tower range required to configure; value/metric TBD',
            'inspectionReach': 'server-authorized item-to-target read reach; separate policy, value/metric TBD',
            'configuredChunks': 'persisted requested chunk selection in tower base, not proof of a successful ticket',
            'effectiveChunks': 'server-confirmed tower-owned live ticket AND observed required load level currently satisfied',
            'effectiveLoadLevel': 'server reports actual required level; numeric/ticking policy TBD, never equate loaded with ticking',
            'externalLoading': 'spawn/player/another tower can load a chunk; this does not count as this tower effective',
            'cleanup': 'removed selections may still have releasing tickets; report them until released, even outside configured area',
        },
        'snapshotFields': {
            'simulated': 'boolean; true in local fixture, not a live server response',
            'source': 'server_snapshot in future; simulated_server_snapshot in fixture',
            'snapshotId': 'server-generated unique snapshot id, not config revision',
            'towerIdentity': 'UUID + dimension + basePos',
            'configRevision': 'tower-base config revision, changes only on writes',
            'observedAtServerTick': 'capture tick; chunk runtime changes do not need a config revision bump',
            'serverSessionId': 'distinguishes server restarts; tick numbers alone are not freshness proof',
            'freshness': 'fresh/stale/unavailable; server freshness budget TBD, stale never shown as current success',
            'towerStatus': 'complete/incomplete/unloaded/replaced/unknown',
            'snapshotStatus': 'ok/partial/unavailable',
            'failureReason': 'null or server reason code for snapshot-level failures',
            'loadingEnabled': 'persisted switch, not proof of actual loading',
            'configuredChunks': 'deduplicated chunk coordinates; dimension inherited from towerIdentity',
            'effectiveChunks': 'coordinates of rows where effectiveByTower == true; null when complete determination unavailable',
            'confirmedEffectiveChunks': 'known effective subset only; not a complete set when effectiveChunks is null',
            'requiredLoadLevel': 'server-authoritative ticket/load-level policy identifier; TBD for this concept',
            'coverageComplete': 'boolean; false means omitted chunks are unknown, never implicitly unloaded',
            'chunks': 'union of configured selection, tower ticket ledger and relevant failures; bounded response, pagination policy TBD',
        },
        'chunkFields': {
            'chunkX': 'integer', 'chunkZ': 'integer', 'configured': 'boolean',
            'state': ['active', 'pending', 'disabled', 'failed', 'releasing', 'unloaded', 'unknown'],
            'failureReason': 'null on healthy rows; stable reason code on failed/unknown rows; do not hide failures in a color',
            'towerTicket': ['active', 'pending', 'releasing', 'none', 'unknown'],
            'observedLoadState': ['loaded', 'unloaded', 'unknown'],
            'requiredLoadLevelSatisfied': 'boolean or null (unknown)',
            'effectiveByTower': 'boolean or null (unknown); requires attributable live tower ticket and satisfied load level',
            'observedAtServerTick': 'per-row observation tick, same snapshot or explicitly older',
            'towerIdentity': 'inherited from enclosing snapshot; never merge rows from another tower identity',
        },
        'errorCodes': ['PERMISSION_DENIED', 'OUT_OF_RANGE', 'TARGET_UNLOADED', 'TOWER_REPLACED', 'TOWER_INCOMPLETE',
                       'BUDGET_EXCEEDED', 'APPLY_FAILED', 'OBSERVATION_UNAVAILABLE', 'SNAPSHOT_STALE'],
        'consistency': 'Capture on server thread or equivalent consistent read; do not combine config revision from one tower with another ticket ledger',
        'unavailable': 'Return explicit unavailable reason; preserve previous snapshot only as stale history, not current effective state',
        'clientRules': ['client never supplies effective status or failure result', 'never infer tower success from a loaded chunk alone',
                        'show configured area and actual effective area separately', 'show chunk coordinates, state and reason',
                        'show tower identity, config revision and snapshot freshness', 'no apply/enable/load buttons'],
    }


def inspector_snapshot():
    rows = [
        # configured, state, failure, ticket, observed load, load-level satisfaction, effective
        (0, 0, True, 'active', None, 'active', 'loaded', True, True),
        (1, 0, True, 'failed', 'BUDGET_EXCEEDED', 'none', 'loaded', True, False),
        (0, 1, True, 'pending', None, 'pending', 'unloaded', False, False),
        (1, 1, True, 'failed', 'APPLY_FAILED', 'none', 'unloaded', False, False),
        (-1, 0, False, 'releasing', None, 'releasing', 'loaded', True, True),
        (2, 0, True, 'unknown', 'OBSERVATION_UNAVAILABLE', 'unknown', 'unknown', None, None),
    ]
    chunks = [dict(zip(('chunkX', 'chunkZ', 'configured', 'state', 'failureReason', 'towerTicket',
                       'observedLoadState', 'requiredLoadLevelSatisfied', 'effectiveByTower'), row),
                   observedAtServerTick=12000) for row in rows]
    return {
        'simulated': True, 'source': 'simulated_server_snapshot',
        'snapshotId': 'concept-snapshot-0001', 'serverSessionId': 'concept-session-0001',
        'towerIdentity': {'uuid': '00000000-0000-4000-8000-000000000001',
                          'dimension': 'minecraft:overworld', 'basePos': [8, 64, 8]},
        'configRevision': 7, 'observedAtServerTick': 12000, 'freshness': 'fresh',
        'snapshotStatus': 'partial', 'failureReason': 'OBSERVATION_UNAVAILABLE', 'towerStatus': 'complete',
        'loadingEnabled': True, 'requiredLoadLevel': 'concept_only_policy_TBD',
        'configuredChunks': [[r['chunkX'], r['chunkZ']] for r in chunks if r['configured']],
        'effectiveChunks': None,
        'confirmedEffectiveChunks': [[r['chunkX'], r['chunkZ']] for r in chunks if r['effectiveByTower'] is True],
        'coverageComplete': True, 'chunks': chunks,
        'note': 'All coordinates/statuses are invented offline examples; loaded external chunk (1,0) is not tower-effective. Unknown row prevents complete effective-set assertion.',
    }


def concept():
    return {
        'schemaVersion': 1, 'status': 'offline_concept_only',
        'name': {'zh_cn': '远仓信号塔控制器', 'en_us': 'Distant Stock Signal Tower Controller'},
        'suggestedRegistryId': 'distantstock:signal_tower_controller', 'registered': False,
        'companionItem': {'concept': 'inspector-concept.json', 'contract': 'inspector-contract.json',
                          'id': 'distantstock:chunk_loader_inspector', 'readOnly': True},
        'placement': {'independentBlock': True, 'requiredTowerStructure': False,
                      'cellCount': 1, 'boundsModelPixels': BOUNDS, 'front': 'north',
                      'towerStructure': 'Complete base + top tower; controller is optional, not a third required component'},
        'meshFormat': 'render_scene face list; not Minecraft block JSON',
        'api': {'build': "build(state='bound') -> list[face]", 'textures': 'textures() -> dict[str, PIL.Image]',
                'materialPrefix': PREFIX, 'states': list(STATES)},
        'states': STATES,
        'stateSemantics': {'boundDoesNotMeanInterconnectEnabled': True,
                           'boundDoesNotMeanChunkLoadingEnabled': True,
                           'failedApplyMustNotShowSuccess': True,
                           'onlyBindingLampChanges': True},
        'materials': {name: MATERIALS[name] for name in STRUCTURAL},
        'materialSource': 'create_materials.material/source; installed Create jar or CREATE_JAR override',
        'originalAccents': ['small semi-transparent binding lens', 'knob pointer'],
        'noImplementationClaims': ['server', 'game GUI', 'chunk tickets', 'rate enforcement', 'registration'],
        'fluid': {'name': None, 'registered': False},
    }


def contract():
    # A declarative proposal, deliberately not a server or transport implementation.
    checks = ['controller_exists_and_actor_can_use', 'same_dimension', 'tower_identity_matches',
              'tower_complete', 'actor_permission', 'controller_within_effective_range',
              'required_chunks_already_loaded', 'device_identity_membership_range_and_type_capability',
              'expected_revision_matches', 'server_budget_and_tower_capacity']
    return {
        'schemaVersion': 1, 'status': 'proposal_not_implemented',
        'transport': None, 'gameGuiImplemented': False,
        'readOnlyInspector': {'item': 'distantstock:chunk_loader_inspector', 'contract': 'inspector-contract.json',
                              'configuredSelectionIsNotEffectiveChunks': True, 'mayLoadOrModify': False},
        'identity': {
            'tower': {'uuid': 'server-generated stable UUID for this tower lifetime',
                      'dimension': 'dimension resource id', 'basePos': ['integer x', 'integer y', 'integer z']},
            'controllerBinding': 'Exactly zero or one tower identity; many controllers may bind the same tower',
            'replacement': 'Rebuild at identical coordinates receives a new UUID; explicit rebind required',
            'device': {'identity': 'server-owned device UUID, lifetime-scoped', 'pos': ['integer x', 'integer y', 'integer z'],
                       'dimension': 'dimension resource id', 'type': 'server-resolved registered device type'},
            'deviceIdentityImplementation': 'Proposed; not claimed to exist in current devices',
        },
        'ownership': {'configuration': 'tower base, never a controller-local copy',
                      'controllerStores': ['towerIdentity'],
                      'clientSnapshot': 'read-only cache with revision and freshness; never authoritative'},
        'range': {'sameDimensionOnly': True, 'effectiveRangeBlocks': None,
                  'status': 'numeric range and metric TBD; server-owned policy, not client-provided',
                  'appliesTo': ['binding', 'snapshot', 'every update', 'every device edit']},
        'loading': {'searchMayLoadChunks': False, 'unloadedTarget': 'reject as unavailable; do not search-load or trust cached completeness',
                    'loadChecksBeforeWorldReads': True},
        'operations': {
            'bind': {'request': ['controllerPos', 'candidateTowerIdentity'],
                     'serverValidation': checks[:7], 'result': 'validated binding or explicit rejection; no implicit coordinate-only adoption'},
            'snapshot': {'request': ['controllerPos'], 'serverValidation': checks[:7],
                         'result': ['towerIdentity', 'revision', 'effectiveConfig', 'deviceTypeCapabilities', 'operationStatus']},
            'update': {'request': ['controllerPos', 'towerIdentity', 'expectedRevision', 'patch'],
                       'serverValidation': checks,
                       'atomic': True, 'unknownFields': 'reject',
                       'conflict': 'reject without overwriting; return fresh revision/snapshot only if still authorized',
                       'success': 'increment revision exactly once only after effective apply; acknowledge effective values',
                       'failure': 'no revision bump or partial config; no success indication; unwind newly acquired future tickets'},
            'unbind': {'scope': 'this controller only', 'permission': 'server validates actor and controller access',
                       'doesNotEraseTowerConfig': True},
        },
        'config': {
            'interconnectEnabled': {'type': 'boolean', 'default': False, 'implemented': False},
            'chunkLoading': {'enabledDefault': False, 'implemented': False,
                             'selectionUnit': 'chunk', 'selection': [{'chunkX': 'integer', 'chunkZ': 'integer'}],
                             'dimension': 'bound tower dimension; cross-dimension selection rejected',
                             'limits': ['deduplicate chunk coordinates', 'bounded request payload', 'server global/player/tower budgets',
                                        'tower capacity', 'server-approved area/range'],
                             'numericLimits': None,
                             'enable': 'validate complete selection and atomically reserve future ticket budget before success',
                             'limitFailure': 'reject whole request; show fault/reason, never a success light',
                             'disable': 'release this tower-owned future tickets',
                             'towerRemovedOrInvalid': 'disable and release future tickets; controller removal alone is not tower removal',
                             'restart': 'revalidate owner, tower identity/completeness and budget before any future ticket recreation'},
            'transferRate': {'implemented': False, 'value': None, 'suggestedUnit': 'packages_per_second',
                             'unitStatus': 'TBD', 'limits': None, 'scope': 'tower parcel transfer',
                             'notHttpBytes': True, 'zeroBurstAndDirectionSemantics': 'TBD; no enforcement in this preview'},
            'devices': {'allowlistDefault': [], 'entry': {'device': 'identity + pos + dimension + type',
                                                        'enabled': 'boolean', 'functions': 'subset of server type capability allowlist'},
                        'validation': ['identity must match loaded server device', 'belongs to bound tower management set',
                                       'same dimension and within effective managed range', 'actor authorized for device',
                                       'device type resolved by server', 'each requested function allowed for that type'],
                        'clientMayInventCapabilities': False,
                        'staleMovedRebuiltDevice': 'reject; never silently retarget coordinates'},
        },
        'deviceTypeCapabilityProposal': {
            'policy': 'Only these reviewed per-type candidates may become server capabilities; unknown type/function denied. All gates below are proposals, not existing tower controls.',
            'dock': {'source': 'src/main/java/dev/distantstock/block/DockBlockEntity.java',
                     'observed': 'package inventory with import/export mode',
                     'candidateFunctions': ['parcel_import', 'parcel_export'], 'mappingStatus': 'TBD implementation'},
            'gauge': {'source': 'src/main/java/dev/distantstock/block/GaugeBlockEntity.java',
                      'observed': 'frequency/address, stock cache information, last order display',
                      'candidateFunctions': ['stock_status_read'], 'otherFunctions': 'TBD after full call-path review'},
            'monitor': {'source': 'src/main/java/dev/distantstock/block/MonitorBlockEntity.java',
                        'observed': 'local/peer timing, backlog, round-trip and link status display',
                        'candidateFunctions': ['link_status_read'], 'otherFunctions': 'TBD'},
            'requester': {'source': 'src/main/java/dev/distantstock/item/RequesterItem.java',
                          'observed': 'handheld item opens requester menu, not a placed block',
                          'candidateFunctions': [], 'supportedAsManagedPlacedDevice': False,
                          'mappingStatus': 'TBD item identity/holder/current-position validation; do not fabricate blockPos'},
        },
        'responses': {'status': ['accepted', 'rejected', 'unavailable', 'conflict'],
                      'errorCodes': ['UNBOUND', 'DIMENSION_MISMATCH', 'OUT_OF_RANGE', 'TOWER_REPLACED', 'TOWER_INCOMPLETE',
                                     'PERMISSION_DENIED', 'TARGET_UNLOADED', 'REVISION_CONFLICT', 'BUDGET_EXCEEDED',
                                     'DEVICE_NOT_MANAGED', 'DEVICE_REPLACED', 'CAPABILITY_DENIED', 'INVALID_PATCH', 'APPLY_FAILED'],
                      'lamp': 'unbound / bound / out_of_range / fault are illustrative binding states; rejected apply never lights success',
                      'chunkLoadingStatusSeparate': True},
        'fluid': {'name': None, 'registerPlaceholder': False},
        'openDecisions': ['range and metric', 'chunk limits and ticket lifetime policy', 'rate unit/value/burst/direction',
                          'device capability mapping', 'requester managed-item model', 'permission integration', 'transport/GUI'],
    }


def projected_bounds(mesh, size, yaw, pitch, scale):
    pts = np.array([p for f in mesh for p in f['points']])
    cam = (pts - [0, 8, 0]) @ (rotation('x', -pitch) @ rotation('y', yaw)).T
    screen = np.column_stack((size[0] / 2 + cam[:, 0] * scale, size[1] / 2 - cam[:, 1] * scale))
    return screen.min(0), screen.max(0)


def view(state, yaw=30, size=(700, 640), scale=22, pitch=22):
    return render(build(state), textures(), size=size, yaw=yaw, pitch=pitch, center=(0, 8, 0), scale=scale)


def previews():
    result = {}
    for label, yaw in (('front', 30), ('back', 210), ('side', 90)):
        im = view('bound', yaw=yaw)
        text(im, (24, 20), 'SIGNAL TOWER CONTROLLER / ' + label.upper(), 20)
        text(im, (24, 607), 'OFFLINE CONCEPT / ONE CELL / CREATE MATERIALS', 14, MUTED)
        result[label + '.png'] = im
    sheet = Image.new('RGBA', (1500, 700), BG)
    header(sheet, 'DISTANT STOCK / SIGNAL TOWER CONTROLLER',
           'Independent optional block / Create andesite casing / mechanical control / 16 x 16 x 16 model pixels')
    for i, (label, yaw) in enumerate((('FRONT / KNOB + BINDING LENS', 30), ('REAR / SERVICE COVER', 210), ('SIDE / CREATE CASING', 90))):
        sheet.paste(view('bound', yaw=yaw, size=(500, 490), scale=16), (i * 500, 112))
        text(sheet, (i * 500 + 25, 610), label, 17)
    text(sheet, (30, 661), 'OFFLINE ART + CONTRACT ONLY / NO GAME GUI, SERVER CONTROLS OR CHUNK LOADING IMPLEMENTED', 16, MUTED)
    result['overview.png'] = sheet
    sheet = Image.new('RGBA', (1600, 650), BG)
    header(sheet, 'CONTROLLER / SIMULATED BINDING STATES',
           'Only the translucent lens changes / BOUND confirms the binding, not interconnect or chunk loading')
    for i, (state, info) in enumerate(STATES.items()):
        im = view(state, size=(400, 420), scale=13)
        sheet.paste(im, (i * 400, 112))
        text(sheet, (i * 400 + 24, 535), info['label'], 21)
        single = view(state)
        text(single, (24, 20), 'SIMULATED / ' + info['label'], 22)
        text(single, (24, 607), 'OFFLINE STATE STUDY / NO SERVER APPLY', 14, MUTED)
        result[state + '.png'] = single
    text(sheet, (30, 592), 'OUT OF RANGE retains identity but blocks edits. FAULT includes unloaded, replaced, invalid or rejected targets.', 16, MUTED)
    text(sheet, (30, 620), 'Chunk loading defaults OFF; a rejected budget/apply never indicates success. All behavior here is proposed.', 15, MUTED)
    result['states.png'] = sheet
    im = render(inspector_build(), textures(), size=(700, 640), yaw=25, pitch=16,
                center=(0, 8, 0), scale=25)
    text(im, (24, 20), 'CHUNK LOADER INSPECTOR / HANDHELD CONCEPT', 20)
    text(im, (24, 562), 'READ-ONLY / SERVER SNAPSHOT PROPOSAL', 17)
    text(im, (24, 590), 'CONFIGURED AREA != EFFECTIVE CHUNKS', 17)
    text(im, (24, 616), 'OFFLINE ITEM STUDY / NO LOADING OR CONFIG WRITES', 13, MUTED)
    result['inspector.png'] = im
    return result


def json_outputs():
    return {'concept.json': concept(), 'control-contract.json': contract(),
            **{f'models/{state}.mesh.json': {'format': 'render_scene', 'state': state, 'faces': build(state)} for state in STATES}}


def validate(check_previews=True):
    checks = 0

    def verify(condition, label):
        nonlocal checks
        if not condition:
            raise AssertionError(label)
        checks += 1

    mats = textures()
    for name in STRUCTURAL:
        verify(mats[PREFIX + name].tobytes() == material(name).tobytes(), name + ' Create material provenance')
        verify(len(set(mats[PREFIX + name].getdata())) > 8, name + ' nonuniform source pixels')
        verify(source(MATERIALS[name]['source']).width >= 16, name + ' actual Create source')
    for name, im in mats.items():
        with Image.open(DOC / 'textures' / (name.split('/')[-1] + '.png')) as saved:
            verify(saved.convert('RGBA').tobytes() == im.tobytes(), name + ' deterministic texture')
    base = build()
    for state in STATES:
        mesh = build(state)
        verify(len(mesh) == len(base), state + ' stable mesh count')
        for f, original in zip(mesh, base):
            verify(all(k in f for k in ('group', 'material', 'points', 'uv')), 'required face fields')
            p, uv = np.asarray(f['points']), np.asarray(f['uv'])
            verify(p.shape == (4, 3) and np.isfinite(p).all(), 'finite quad')
            verify(uv.shape == (4, 2) and np.isfinite(uv).all(), 'finite UV')
            verify((p >= BOUNDS[0]).all() and (p <= BOUNDS[1]).all(), 'one-cell bounds')
            verify(f['material'] in mats and f['group'].startswith(PREFIX), 'namespaced material/group')
            verify(f['points'] == original['points'] and f['uv'] == original['uv'], 'state geometry invariant')
            verify(f['material'] == original['material'] or f['group'] == PREFIX + 'binding_lamp', 'only binding lens changes')
            verify(np.linalg.norm(np.cross(p[1] - p[0], p[2] - p[0])) > 0, 'nondegenerate quad')
        alpha = mats[PREFIX + 'lens_' + state].getchannel('A').getextrema()
        verify(0 < alpha[0] <= alpha[1] < 255, state + ' semi-transparent lens')
        for size, scale in (((700, 640), 22), ((500, 490), 16), ((400, 420), 13)):
            for yaw in (30, 210, 90):
                lo, hi = projected_bounds(mesh, size, yaw, 22, scale)
                verify((lo >= [22, 60]).all() and (hi <= [size[0] - 22, size[1] - 45]).all(), 'projection clear of canvas and labels')
    for name, expected in json_outputs().items():
        verify(json.loads((DOC / name).read_text()) == expected, name + ' deterministic JSON')
    verify((DOC / 'README.md').exists(), 'contract documentation')
    c = contract()
    verify(c['config']['chunkLoading']['enabledDefault'] is False, 'chunk loading disabled by default')
    verify(c['config']['transferRate']['notHttpBytes'] and not c['config']['transferRate']['implemented'], 'rate proposal only')
    verify(c['config']['devices']['clientMayInventCapabilities'] is False, 'server type whitelist')
    verify(c['loading']['searchMayLoadChunks'] is False, 'no implicit chunk loads')
    verify(not concept()['placement']['requiredTowerStructure'], 'optional controller')
    verify(c['deviceTypeCapabilityProposal']['requester']['supportedAsManagedPlacedDevice'] is False, 'requester remains handheld')
    if check_previews:
        for name, expected in previews().items():
            with Image.open(OUT / ('tower-controller-' + name)) as saved:
                verify(saved.size == expected.size and saved.convert('RGBA').tobytes() == expected.tobytes(), name + ' repeatable uncropped preview')
    print(f'PASS {checks} validations; {len(base)} faces/state; {len(mats)} textures; {len(STATES)} simulated states; no gameplay implementation')
    return checks


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='Read-only validation of outputs, provenance, bounds and deterministic rendering')
    args = parser.parse_args()
    if not args.check:
        for name, obj in json_outputs().items():
            path = DOC / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + '\n')
        (DOC / 'textures').mkdir(parents=True, exist_ok=True)
        for name, im in textures().items():
            im.save(DOC / 'textures' / (name.split('/')[-1] + '.png'))
        OUT.mkdir(parents=True, exist_ok=True)
        for name, im in previews().items():
            im.save(OUT / ('tower-controller-' + name))
    validate()
    print(OUT / 'tower-controller-overview.png')
    print(OUT / 'tower-controller-states.png')


if __name__ == '__main__':
    main()
