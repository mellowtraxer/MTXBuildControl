import unittest

from nexus.github import GitHubError
from nexus.registry import classify, inventory
from nexus.runner import BASE_LABELS, select_runner, supervise


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


class Worker:
    def __init__(self, states, fail_dispatch=False):
        self.states = iter(states)
        self.posts = []
        self.fail_dispatch = fail_dispatch
        self.now = 0

    def request(self, path, payload=None):
        if payload is not None:
            self.posts.append((path, payload))
        if path.endswith("dispatches"):
            if self.fail_dispatch:
                raise GitHubError(503)
            return {"workflow_run_id": 42}
        if path.endswith("cancel"):
            return None
        return next(self.states)

    def sleep(self, seconds):
        self.now += seconds

    def run(self):
        return supervise(self, "owner/private", "worker.yml", "main", "a"*40, 10, 15,
                         clock=lambda: self.now, sleep=self.sleep)


class SupervisionTests(unittest.TestCase):
    def test_success(self):
        worker = Worker([{"status": "queued"}, {"status": "in_progress"},
                         {"status": "completed", "conclusion": "success"}])
        self.assertEqual(worker.run(), ("pc", "worker-succeeded", 42))
        self.assertEqual(len(worker.posts), 1)
        self.assertEqual(worker.posts[0][1]["inputs"]["source_sha"], "a"*40)

    def test_queue_timeout_and_offline_mid_build(self):
        for status, reason, count in (("queued", "worker-queue-timeout", 3),
                                       ("in_progress", "worker-execution-timeout", 4)):
            with self.subTest(status=status):
                worker = Worker([{"status": status}] * count)
                self.assertEqual(worker.run(), ("cloud", reason, 42))
                self.assertTrue(worker.posts[1][0].endswith("/42/cancel"))

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


if __name__ == "__main__":
    unittest.main()
