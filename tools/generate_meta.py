#!/usr/bin/env python3
"""Creates missing Unity .meta files under Assets/ with stable, deterministic GUIDs.

Unity would generate these itself, but committing them keeps GUIDs identical on every machine (asset references,
assembly definitions and scenes stay valid across clones). Existing .meta files are never modified.

    python3 tools/generate_meta.py
"""
import hashlib
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "Assets")

TAIL = "  userData: \n  assetBundleName: \n  assetBundleVariant: \n"
TEMPLATES = {
    "folder": "folderAsset: yes\nDefaultImporter:\n  externalObjects: {}\n" + TAIL,
    ".cs": "MonoImporter:\n  externalObjects: {}\n  serializedVersion: 2\n  defaultReferences: []\n  executionOrder: 0\n  icon: {instanceID: 0}\n" + TAIL,
    ".asmdef": "AssemblyDefinitionImporter:\n  externalObjects: {}\n" + TAIL,
    ".shader": "ShaderImporter:\n  externalObjects: {}\n  defaultTextures: []\n  nonModifiableTextures: []\n" + TAIL,
    "default": "DefaultImporter:\n  externalObjects: {}\n" + TAIL,
}


def guid_for(rel_path: str) -> str:
    return hashlib.md5(("cubeclash:" + rel_path.replace(os.sep, "/")).encode()).hexdigest()


def write_meta(path: str, kind: str) -> bool:
    meta = path + ".meta"
    if os.path.exists(meta):
        return False
    rel = os.path.relpath(path, ROOT)
    body = TEMPLATES.get(kind, TEMPLATES["default"])
    with open(meta, "w", newline="\n") as f:
        f.write(f"fileFormatVersion: 2\nguid: {guid_for(rel)}\n{body}")
    return True


def main() -> int:
    created = 0
    for dirpath, dirnames, filenames in os.walk(ASSETS):
        dirnames[:] = [d for d in dirnames if not d.startswith(".")]
        for d in dirnames:
            created += write_meta(os.path.join(dirpath, d), "folder")
        for name in filenames:
            if name.endswith(".meta") or name.startswith("."):
                continue
            ext = os.path.splitext(name)[1].lower()
            created += write_meta(os.path.join(dirpath, name), ext)
    print(f"created {created} meta file(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
