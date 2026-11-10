import unittest

from nexus.github import GitHubError
from nexus.registry import classify, inventory
from nexus.runner import BASE_LABELS, route_for_run, select_runner, supervise


def runner(status="online", busy=False, name="RICCI"):
    return {"status": status, "busy": busy, "name": name, "os": "Windows",
            "labels": [{"name": label} for label in BASE_LABELS | {"Android-Build"}]}


class RunnerTests(unittest.TestCase):
    def route(self, runners, private=True, event="push", trusted=True, preference="auto"):
        return select_runner({"private": private}, event, trusted, preference, "android-build", runners)

    def test_runner_state_and_override(self):
        self.assertEqual(self.route([runner()]), ("pc", "runner-available"))
        self.assertEqual(self.route([runner("offline")]), ("cloud", "runner-offline"))
        self.assertEqual(self.route([runner(busy=True)]), ("cloud", "runner-busy"))
        self.assertEqual(self.route([runner(), runner(name="other")]),
                         ("cloud", "runner-missing-or-label-collision"))
        self.assertEqual(self.route([runner()], preference="github"), ("cloud", "cloud-override"))

    def test_untrusted_event_public_repository_and_ref(self):
        for kwargs in ({"private": False}, {"event": "pull_request_target"}, {"trusted": False}):
            self.assertEqual(self.route([runner()], **kwargs), ("cloud", "repository-or-ref-ineligible"))

    def test_missing_capability(self):
        candidate = runner()
        candidate["labels"] = [{"name": label} for label in BASE_LABELS]
        self.assertEqual(self.route([candidate]), ("cloud", "runner-missing-or-label-collision"))


    def test_github_api_error_is_unknown_and_falls_back(self):
        class UnavailableApi:
            def request(self, path):
                raise GitHubError(503)

        result = route_for_run(UnavailableApi(), "", "owner/private", "push", True,
                               "auto", "android-build")
        self.assertEqual(result, ("cloud", "runner-status-unavailable"))


class Worker:
    def __init__(self, states, fail_dispatch=False, job_status="in_progress", artifacts=True):
        self.states = iter(states)
        self.posts = []
        self.fail_dispatch = fail_dispatch
        self.now = 0
        self.job_status = job_status
        self.artifacts = artifacts

    def request(self, path, payload=None):
        if payload is not None:
            self.posts.append((path, payload))
        if path.endswith("dispatches"):
            if self.fail_dispatch:
                raise GitHubError(503)
            return {"workflow_run_id": 42}
        if path.endswith("cancel"):
            return None
        if path.endswith("/jobs"):
            return {"jobs": [{"status": self.job_status, "runner_name": "RICCI",
                              "conclusion": "skipped" if self.job_status == "skipped" else "success"}]}
        return next(self.states)

    def pages(self, path, field=None):
        return iter([{"name": "build-apk", "expired": False, "size_in_bytes": 100}] if self.artifacts else [])

    def sleep(self, seconds):
        self.now += seconds

    def run(self):
        return supervise(self, "owner/private", "worker.yml", "main", "a"*40, "build-apk", 10, 15,
                         clock=lambda: self.now, sleep=self.sleep)


class SupervisionTests(unittest.TestCase):
    def test_success(self):
        worker = Worker([{"status": "queued"}, {"status": "in_progress"},
                         {"status": "completed", "conclusion": "success"}])
        self.assertEqual(worker.run(), ("pc", "worker-succeeded", 42))
        self.assertEqual(len(worker.posts), 1)
        self.assertEqual(worker.posts[0][1]["inputs"]["source_sha"], "a"*40)

    def test_success_requires_the_real_pc_job_and_artifact(self):
        success = {"status": "completed", "conclusion": "success"}
        self.assertEqual(Worker([success], job_status="skipped").run(),
                         ("cloud", "worker-job-not-verified", 42))
        self.assertEqual(Worker([success], artifacts=False).run(),
                         ("cloud", "worker-artifact-not-verified", 42))

    def test_queue_timeout_and_offline_mid_build(self):
        for status, reason, count in (("queued", "worker-queue-timeout", 3),
                                       ("in_progress", "worker-execution-timeout", 4)):
            with self.subTest(status=status):
                worker = Worker([{"status": status}] * count)
                self.assertEqual(worker.run(), ("cloud", reason, 42))
                self.assertTrue(worker.posts[1][0].endswith("/42/cancel"))

    def test_active_workflow_with_queued_pc_job_uses_queue_timeout(self):
        worker = Worker([{"status": "in_progress"}] * 3, job_status="queued")
        self.assertEqual(worker.run(), ("cloud", "worker-queue-timeout", 42))
        self.assertEqual(worker.now, 10)

    def test_dispatch_failure_is_not_retried(self):
        worker = Worker([], fail_dispatch=True)
        self.assertEqual(worker.run(), ("cloud", "dispatch-unconfirmed", None))
        self.assertEqual(len(worker.posts), 1)

    def test_failed_worker_falls_back_without_cancelling(self):
        worker = Worker([{"status": "completed", "conclusion": "failure"}])
        self.assertEqual(worker.run(), ("cloud", "worker-failed", 42))
        self.assertEqual(len(worker.posts), 1)


class RegistryTests(unittest.TestCase):
    def test_project_classification(self):
        repo = {"name": "example"}
        self.assertEqual(classify(repo, {"app/src/main/AndroidManifest.xml"}), "android")
        self.assertEqual(classify(repo, {"project.godot", "export_presets.cfg"}), "godot")
        self.assertEqual(classify(repo, {"some.file"}), "unknown")
        self.assertEqual(classify({"name": "Example-Updates"}, {"manifest.json"}), "release")

    def test_stable_identity_rename_and_scope(self):
        def repo(name, owner="mine", repo_id=101):
            return {"id": repo_id, "name": name, "full_name": f"{owner}/{name}",
                    "owner": {"login": owner}, "private": True, "archived": False,
                    "default_branch": "main"}

        class AppApi:
            def pages(self, path, field=None):
                if path == "/installation/repositories":
                    return iter([repo("old"), repo("new"), repo("other", "else", 202)])
                return iter([{"name": "main", "commit": {"sha": "a"*40}}])

            def request(self, path):
                return {"truncated": False, "tree": [
                    {"path": "project.godot", "type": "blob"},
                    {"path": ".github/workflows/android.yml", "type": "blob"},
                ]}

        result = inventory(AppApi(), "mine")
        self.assertEqual(result["schemaVersion"], 1)
        self.assertEqual(len(result["projects"]), 1)
        self.assertEqual(result["projects"][0]["repository"], "mine/new")
        self.assertTrue(result["projects"][0]["private"])
        self.assertEqual(result["projects"][0]["type"], "godot")


    def test_branch_workflows_and_repo_removed_during_scan(self):
        class AppApi:
            def pages(self, path, field=None):
                if path == "/installation/repositories":
                    return iter([
                        {"id": i, "name": name, "full_name": f"mine/{name}",
                         "owner": {"login": "mine"}, "private": True,
                         "archived": name == "app", "default_branch": "main"}
                        for i, name in enumerate(("gone", "app"))
                    ])
                if path == "/repos/mine/gone/branches":
                    raise GitHubError(404)
                return iter([
                    {"name": name, "commit": {"sha": sha}}
                    for name, sha in (("main", "a"*40), ("feature/app", "b"*40))
                ])

            def request(self, path):
                if path.endswith("a"*40 + "?recursive=1"):
                    return {"truncated": False, "tree": []}
                return {"truncated": False, "tree": [
                    {"path": "app/src/main/AndroidManifest.xml", "type": "blob"},
                    {"path": ".github/workflows/android.yml", "type": "blob"},
                ]}

        projects = inventory(AppApi(), "mine")["projects"]
        self.assertEqual([project["repository"] for project in projects], ["mine/app"])
        self.assertTrue(projects[0]["archived"])
        feature = projects[0]["branchDetails"]["feature/app"]
        self.assertEqual(feature["type"], "android")
        self.assertEqual(feature["workflows"], [".github/workflows/android.yml"])


if __name__ == "__main__":
    unittest.main()
