#!/usr/bin/env python3
"""Compatibility entry point: regenerate current art, never the retired 0.3.6 art."""
from gen_block_art import main as blocks
from gen_item_art import main as items

if __name__ == '__main__':
    blocks()
    items()
