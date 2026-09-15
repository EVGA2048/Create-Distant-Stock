# -*- coding: utf-8 -*-
"""Unify core side faces with the selected remote casing."""
from pathlib import Path
OUT=Path(__file__).resolve().parent
previous=OUT.parent/'ether_resonator_v2/render_proposal.py'
script=previous.read_text()
script=script.replace("script=previous.read_text()", "script=previous.read_text()\nscript=script.replace(\"(16,16,16),'andesite',faces={'down':'gearbox','up':'polished'}\", \"(16,16,16),'casing',faces={'down':'gearbox','up':'polished'}\")")
scope={'__file__':str(OUT/'render_proposal.py')}
exec(compile(script,str(previous),'exec'),scope)
# The preceding script runs its reused geometry in another scope; reproduce
# just that scope to export an additional base detail without changing geometry.
inner=scope['script']
ctx={'__file__':str(OUT/'render_proposal.py')}
exec(compile(inner,'resonator_casing_variant','exec'),ctx)
ctx['view'](ctx['base'],(760,640),(8,4,8),11).save(OUT/'base_detail.png')
ctx['view'](ctx['core']+ctx['shaft'],(420,420),(8,4,8),14,pitch=-24).save(OUT/'core_detail.png')
