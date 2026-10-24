"""Read-only documentation links, JSON and validation-script syntax checks."""

import ast
import json
import re
import sys
from pathlib import Path
from urllib.parse import unquote


ROOT = Path(__file__).resolve().parents[2]
LINK = re.compile(r"!?\[[^\]]*\]\((<[^>]+>|[^\s)]+)(?:\s+\"[^\"]*\")?\)")


def without_fences(text):
    return re.sub(r"(?ms)^\s*```[^\n]*\n.*?^\s*```[^\n]*$", "", text)


def heading_ids(text):
    seen = {}
    ids = set()
    for heading in re.findall(r"(?m)^#{1,6}\s+(.+?)\s*#*\s*$", without_fences(text)):
        heading = re.sub(r"<[^>]*>", "", heading)
        heading = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", heading)
        slug = re.sub(r"[^\w\s-]", "", heading.lower()).replace(" ", "-")
        suffix = seen.get(slug, 0)
        ids.add(slug + (f"-{suffix}" if suffix else ""))
        seen[slug] = suffix + 1
    ids.update(re.findall(r'(?:id|name)=[\"\']([^\"\']+)[\"\']', text))
    return ids


def main():
    docs = ROOT / "docs"
    frontend_docs = [path for path in (ROOT / "frontend").rglob("*.md")
                     if not any(part in {"node_modules", "dist"} for part in path.parts)
                     and not path.is_relative_to(ROOT / "frontend/licenses/vendor")
                     and not path.is_relative_to(ROOT / "frontend/licenses/artifact")]
    markdown = sorted(set([*docs.rglob("*.md"), *frontend_docs, ROOT / "README.md", ROOT / "AGENTS.md"]))
    errors = []
    links = 0
    cache = {}
    for source in markdown:
        try:
            text = source.read_text(encoding="utf-8-sig")
        except (OSError, UnicodeError) as error:
            errors.append({"source": str(source.relative_to(ROOT)), "error": str(error)})
            continue
        for match in LINK.finditer(without_fences(text)):
            url = unquote(match.group(1).strip("<>"))
            if re.match(r"^[a-zA-Z][a-zA-Z0-9+.-]*:", url):
                continue
            path, _, anchor = url.partition("#")
            target = (source.parent / path).resolve() if path else source
            links += 1
            error = None
            if not target.exists():
                error = "missing target"
            elif anchor and target.suffix.lower() == ".md":
                if target not in cache:
                    cache[target] = heading_ids(target.read_text(encoding="utf-8-sig"))
                if anchor not in cache[target]:
                    error = "missing heading anchor"
            if error:
                errors.append({"source": str(source.relative_to(ROOT)), "target": url, "error": error})
    json_files = sorted(docs.rglob("*.json"))
    for source in json_files:
        try:
            json.loads(source.read_text(encoding="utf-8-sig"))
        except (ValueError, OSError) as error:
            errors.append({"source": str(source.relative_to(ROOT)), "error": str(error)})
    python_files = sorted(set([*docs.rglob("*.py")]))
    for source in python_files:
        try:
            ast.parse(source.read_text(encoding="utf-8-sig"))
        except (SyntaxError, OSError) as error:
            errors.append({"source": str(source.relative_to(ROOT)), "error": str(error)})
    result = {"passed": not errors, "markdownFiles": len(markdown), "localLinks": links, "jsonFiles": len(json_files), "pythonFiles": len(python_files), "errors": errors}
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if not errors else 1


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    raise SystemExit(main())
