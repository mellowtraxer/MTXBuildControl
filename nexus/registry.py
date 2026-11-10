"""Build a private inventory from a GitHub App installation token."""

import argparse
import json
import os
from pathlib import Path
from urllib.parse import quote

from nexus.github import GitHub, GitHubError


def classify(repo, paths):
    name = repo["name"].casefold()
    if not paths:
        return "empty"
    if "project.godot" in paths:
        return "godot"
    if any(path.endswith("/src/main/AndroidManifest.xml") for path in paths):
        return "android"
    if name.endswith("-updates"):
        return "release"
    if paths & {"build.gradle", "build.gradle.kts", "pom.xml"}:
        return "jvm"
    if paths & {"pyproject.toml", "requirements.txt", "setup.py"}:
        return "python"
    if "package.json" in paths:
        return "web" if paths & {"index.html", "public/index.html"} else "node"
    if "index.html" in paths:
        return "web"
    if any(path.endswith((".sln", ".csproj")) for path in paths):
        return "desktop"
    if any(path.startswith(".github/workflows/") for path in paths):
        return "infrastructure"
    return "unknown"


def inventory(api, owner):
    projects = {}
    for repo in api.pages("/installation/repositories", "repositories"):
        if repo["owner"]["login"].casefold() != owner.casefold():
            continue
        name = repo["full_name"]
        default = repo["default_branch"]
        endpoint = f"/repos/{name}"
        branches = list(api.pages(endpoint + "/branches"))
        heads = {branch["name"]: branch["commit"]["sha"] for branch in branches}
        paths = set()
        analysis = "complete"
        if default in heads:
            tree = api.request(endpoint + "/git/trees/" + quote(heads[default], safe="") + "?recursive=1")
            if tree["truncated"]:
                analysis = "truncated"
            paths = {entry["path"] for entry in tree["tree"] if entry["type"] == "blob"}
        elif branches:
            analysis = "default-branch-unavailable"
        workflows = sorted(path for path in paths if path.startswith(".github/workflows/")
                           and path.endswith((".yml", ".yaml")))
        projects[repo["id"]] = {
            "id": repo["id"], "repository": name, "private": repo["private"],
            "archived": repo["archived"], "defaultBranch": default, "branches": heads,
            "type": classify(repo, paths) if analysis == "complete" else "unknown",
            "workflows": workflows, "analysis": analysis,
        }
    return {"schemaVersion": 1, "scope": "github-app-installation",
            "projects": sorted(projects.values(), key=lambda project: project["id"])}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--owner", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = inventory(GitHub(os.environ["NEXUS_INSTALLATION_TOKEN"]), args.owner)
    except GitHubError as exc:
        parser.exit(1, f"{exc}; no inventory written.\n")
    args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
