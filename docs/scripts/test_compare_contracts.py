import unittest
from compare_contracts import compare


class CompareContractTests(unittest.TestCase):
    def test_only_explicit_dynamic_fields_may_change(self):
        first = {'events': [{'type': 'session', 'file': 'a'}, {'type': 'done'}]}
        second = {'events': [{'type': 'session', 'file': 'b'}, {'type': 'done'}]}
        self.assertFalse(compare(first, second))
        self.assertTrue(compare(first, second, ['/events/0/file']))

    def test_order_business_error_and_file_bytes_are_not_normalized(self):
        first = {'events': [{'type': 'text'}, {'type': 'done'}], 'files': [{'path': '画布.json', 'sha256': 'a'}]}
        self.assertFalse(compare(first, {**first, 'events': list(reversed(first['events']))}))
        self.assertFalse(compare(first, {**first, 'events': [{'type': 'text'}, {'type': 'error'}]}))
        self.assertFalse(compare(first, {**first, 'files': [{'path': '画布.json', 'sha256': 'b'}]}))
        self.assertFalse(compare({'code': 200}, {'code': 404}))


if __name__ == '__main__':
    unittest.main()
