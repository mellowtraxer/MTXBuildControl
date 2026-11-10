"""Build a private inventory from a GitHub App installation token."""

import argparse
import json
import os
from pathlib import Path
from urllib.parse import quote

from nexus.github import GitHub, GitHubError


def classify(repo, paths):
    name = repo["name"].casefold()
    if name.endswith("-updates"):
        return "release"
    if not paths:
        return "empty"
    if "project.godot" in paths:
        return "godot"
    if any(path.endswith("/src/main/AndroidManifest.xml") for path in paths):
        return "android"
    if paths & {"build.gradle", "build.gradle.kts", "pom.xml"} or any(
        path.endswith(".java") for path in paths
    ):
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
        try:
            branches = list(api.pages(endpoint + "/branches"))
            heads = {branch["name"]: branch["commit"]["sha"] for branch in branches}
            trees = {}
            for sha in dict.fromkeys(heads.values()):
                tree = api.request(endpoint + "/git/trees/" + quote(sha, safe="") + "?recursive=1")
                paths = {entry["path"] for entry in tree["tree"] if entry["type"] == "blob"}
                trees[sha] = paths, "truncated" if tree["truncated"] else "complete"
        except GitHubError as exc:
            if exc.status not in {404, 410}:
                raise
            # Installation access or the repository may change while scanning.
            continue

        branch_details = {}
        for branch, sha in heads.items():
            paths, analysis = trees[sha]
            branch_details[branch] = {
                "type": classify(repo, paths) if analysis == "complete" else "unknown",
                "workflows": sorted(path for path in paths if path.startswith(".github/workflows/")
                                    and path.endswith((".yml", ".yaml"))),
                "analysis": analysis,
            }
        default_details = branch_details.get(default, {
            "type": "unknown" if heads else classify(repo, set()),
            "workflows": [],
            "analysis": "default-branch-unavailable" if heads else "complete",
        })
        projects[repo["id"]] = {
            "id": repo["id"], "repository": name, "private": repo["private"],
            "archived": repo["archived"], "defaultBranch": default, "branches": heads,
            "branchDetails": branch_details, **default_details,
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
