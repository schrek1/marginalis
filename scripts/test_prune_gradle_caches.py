import unittest

from prune_gradle_caches import superseded


def cache(key: str, created: str, used: str) -> dict:
    return {"key": key, "created_at": created, "last_accessed_at": used, "size_in_bytes": 1, "id": key}


class SupersededTest(unittest.TestCase):

    def test_the_entry_in_use_stays_although_a_newer_one_of_its_kind_exists(self):
        in_use = cache("gradle-dependencies-v2-aaaaaaaaaaaa1", "2026-10-18T06:00", "2026-10-18T12:00")
        replaced = cache("gradle-dependencies-v2-bbbbbbbbbbbb2", "2026-10-18T10:00", "2026-10-18T11:00")

        stale = superseded([in_use, replaced])

        self.assertEqual([replaced["key"]], [entry["key"] for entry in stale])

    def test_each_kind_keeps_its_own_entry(self):
        dependencies = cache("gradle-dependencies-v2-aaaaaaaaaaaa1", "2026-10-18T06:00", "2026-10-18T06:00")
        transforms = cache("gradle-transforms-v2-cccccccccccc3", "2026-10-18T09:00", "2026-10-18T09:00")

        stale = superseded([dependencies, transforms])

        self.assertEqual([], stale)

    def test_caches_other_than_gradle_entries_are_left_alone(self):
        index = cache("ide-layout-index-Linux-ipgp2.18.1-ide2025.2", "2026-10-18T01:00", "2026-10-18T01:00")
        old_index = cache("ide-layout-index-395ad0eadcd7fc5c6ff041081f07f9b0", "2026-10-17T01:00", "2026-10-17T01:00")

        stale = superseded([index, old_index])

        self.assertEqual([], stale)


if __name__ == "__main__":
    unittest.main()
