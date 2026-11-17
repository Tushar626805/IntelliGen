"""Tests for FlaskProject/app.py (run:  python docs/verification/test_flask_pipeline.py).
The Ollama model is replaced by a scripted fake, so these tests check the pipeline logic, not model quality."""
import json, os, sys, time, types, unittest

sys.modules['ollama'] = types.ModuleType('ollama')           # stub: the real model is never called
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', '..', 'FlaskProject'))
import app as A

P1 = ("Photosynthesis is the process by which green plants convert light energy into chemical energy stored in glucose. "
      "It takes place mainly in the chloroplasts, which contain the pigment chlorophyll. "
      "Chlorophyll absorbs red and blue light and reflects green light, which is why leaves look green.")
P2 = ("The light-dependent reactions occur in the thylakoid membranes and produce ATP and NADPH. "
      "The Calvin cycle takes place in the stroma and uses ATP and NADPH to fix carbon dioxide into sugars. "
      "Oxygen is released as a by-product when water molecules are split during the light-dependent reactions.")
P3 = ("Cellular respiration releases energy from glucose in the mitochondria. "
      "Glycolysis, the first stage, occurs in the cytoplasm and splits one glucose molecule into two pyruvate molecules. "
      "The Krebs cycle then takes place in the mitochondrial matrix, producing carbon dioxide, ATP and electron carriers.")
DOC = P1 + '\f' + P2 + '\f' + P3

GOOD = {
    'pigment': {'question': 'Which pigment in the chloroplasts absorbs red and blue light?',
                'options': ['Chlorophyll', 'Carotene', 'Melanin', 'Haemoglobin'], 'answer': 'Chlorophyll'},
    'calvin': {'question': 'Where does the Calvin cycle take place?',
               'options': ['In the stroma', 'In the thylakoid membranes', 'In the cytoplasm', 'In the nucleus'], 'answer': 'In the stroma'},
    'glyco': {'question': 'In which part of the cell does glycolysis occur?',
              'options': ['A) In the cytoplasm', 'B) In the nucleus', 'C) In the ribosome', 'D) In the vacuole'], 'answer': 'A'},
    'green': {'question': 'Why do leaves look green?',
              'options': ['Chlorophyll reflects green light', 'Leaves absorb green light', 'Glucose is green', 'Oxygen is green'],
              'answer': 'Chlorophyll reflects green light'},
}
KEYWORD = {'pigment': 'chlorophyll absorbs', 'calvin': 'calvin cycle', 'glyco': 'glycolysis', 'green': 'leaves look green'}
BAD = [
    {'question': 'Who discovered penicillin in 1928?', 'options': ['Fleming', 'Pasteur', 'Koch', 'Lister'], 'answer': 'Fleming'},
    {'question': 'Which pigment inside chloroplasts absorbs red and blue light?',
     'options': ['Chlorophyll', 'Carotene', 'Melanin', 'Haemoglobin'], 'answer': 'Chlorophyll'},   # duplicate of 'pigment'
    {'question': 'What does the Krebs cycle produce in the matrix?',
     'options': ['Carbon dioxide', 'Methane', 'Helium', 'Nitrogen'], 'answer': 'Mitosis'},          # answer not an option
    {'question': 'What is released when water molecules are split?',
     'options': ['Oxygen', 'Helium', 'Neon', 'All of the above'], 'answer': 'Oxygen'},
    {'question': 'What do the light-dependent reactions produce?', 'options': ['ATP', 'NADPH', 'Glucose'], 'answer': 'ATP'},
    {'question': 'What is stored in glucose in plants?', 'options': ['Energy', 'Energy', 'Water', 'Salt'], 'answer': 'Energy'},
]


def fake_llm_factory(include_bad=True, calls=None):
    def llm(chunk_text, count, difficulty, avoid):
        if calls is not None:
            calls.append({'len': len(chunk_text), 'count': count, 'difficulty': difficulty, 'avoid': list(avoid)})
        low = chunk_text.lower()
        items = [v for k, v in GOOD.items() if KEYWORD[k] in low and v['question'] not in avoid]
        return (BAD[:] if include_bad else []) + items
    return llm


class Units(unittest.TestCase):
    def test_parse_items_variants(self):
        self.assertEqual(len(A.parse_items('{"questions":[{"question":"a"},{"question":"b"}]}')), 2)
        self.assertEqual(len(A.parse_items('```json\n[{"question":"a"}]\n```')), 1)
        self.assertEqual(len(A.parse_items('Sure! Here you go: {"data":[{"question":"a"}]} hope it helps')), 1)
        self.assertEqual(len(A.parse_items('{"question":"solo"}')), 1)
        self.assertEqual(A.parse_items('not json at all'), [])
        self.assertEqual(A.parse_items(None), [])

    def test_resolve_answer_never_guesses(self):
        o = ['Alpha', 'Beta', 'Gamma', 'Delta']
        self.assertEqual(A.resolve_answer('B', o), 'Beta')
        self.assertEqual(A.resolve_answer('option c', o), 'Gamma')
        self.assertEqual(A.resolve_answer('(D)', o), 'Delta')
        self.assertEqual(A.resolve_answer('1', o), 'Beta')
        self.assertEqual(A.resolve_answer('  gamma. ', o), 'Gamma')
        self.assertIsNone(A.resolve_answer('Epsilon', o))
        self.assertIsNone(A.resolve_answer(None, o))
        self.assertIsNone(A.resolve_answer('', o))

    def test_prepare_pages_and_sentences(self):
        s = A.prepare(DOC)
        pages = {p for p, _ in s}
        self.assertEqual(pages, {1, 2, 3})
        self.assertIsNone(A.prepare(P1)[0][0])                       # no form feeds -> no page numbers

    def test_validate_good_and_support_levels(self):
        sents = A.prepare(DOC)
        q, why = A.validate_item(GOOD['calvin'], sents, [], 'medium')
        self.assertIsNone(why); self.assertEqual(q['support'], 'good'); self.assertEqual(q['source_page'], 2)
        self.assertIn('Calvin cycle', q['source_excerpt']); self.assertIn(q['answer'], q['options'])
        q, why = A.validate_item(GOOD['glyco'], sents, [], 'easy')     # letter answer + prefixed options
        self.assertIsNone(why); self.assertEqual(q['answer'], 'In the cytoplasm'); self.assertEqual(q['source_page'], 3)
        self.assertTrue(all(not o.startswith(('A)', 'B)')) for o in q['options']))

    def test_each_bad_item_is_rejected_with_a_reason(self):
        sents, accepted = A.prepare(DOC), []
        q, _ = A.validate_item(GOOD['pigment'], sents, accepted, 'medium'); accepted.append(q)
        reasons = [A.validate_item(b, sents, accepted, 'medium')[1] for b in BAD]
        self.assertIn('could not be matched', reasons[0])
        self.assertIn('duplicated', reasons[1])
        self.assertIn('did not match any', reasons[2])
        self.assertIn('all/none', reasons[3])
        self.assertIn('exactly 4', reasons[4])
        self.assertIn('identical', reasons[5])
        self.assertTrue(all(reasons))

    def test_warnings(self):
        sents = A.prepare(DOC)
        item = {'question': 'Where does the Calvin cycle take place?',
                'options': ['In the stroma where the cycle fixes carbon dioxide into sugars', 'Nucleus', 'Vacuole', 'Ribosome'],
                'answer': 'In the stroma where the cycle fixes carbon dioxide into sugars'}
        q, why = A.validate_item(item, sents, [], 'medium')
        self.assertIsNone(why)
        self.assertTrue(any('much longer' in w for w in q['warnings']))

    def test_options_are_shuffled_but_answer_survives(self):
        sents = A.prepare(DOC); positions = set()
        for _ in range(40):
            q, _ = A.validate_item(GOOD['pigment'], sents, [], 'medium')
            positions.add(q['options'].index(q['answer'])); self.assertEqual(q['answer'], 'Chlorophyll')
        self.assertGreater(len(positions), 1)

    def test_allocate(self):
        for n in range(1, 31):
            for parts in range(1, 6):
                self.assertEqual(sum(A.allocate(n, parts)), n)


class Pipeline(unittest.TestCase):
    def setUp(self):
        self.old = (A.CHUNK_CHARS, A.MAX_CHUNKS); A.CHUNK_CHARS, A.MAX_CHUNKS = 300, 3

    def tearDown(self):
        A.CHUNK_CHARS, A.MAX_CHUNKS = self.old

    def test_full_run_filters_bad_items_and_reports_them(self):
        calls = []
        r = A.run_pipeline(DOC, 4, 'hard', llm=fake_llm_factory(True, calls))
        qs = r['questions']
        self.assertEqual(len(qs), 4)
        self.assertEqual(len({q['question'] for q in qs}), 4)
        self.assertTrue(all(q['answer'] in q['options'] and q['difficulty'] == 'hard' for q in qs))
        self.assertTrue(all(c['difficulty'] == 'hard' for c in calls))
        self.assertTrue(r['rejected'], 'rejected drafts must be reported to the teacher')
        self.assertEqual(r['meta']['shortfall'], 0)
        self.assertIn('do not verify', r['meta']['checks'])
        self.assertEqual({q['source_page'] for q in qs} <= {1, 2, 3}, True)

    def test_coverage_spans_document_when_too_long(self):
        long_doc = (DOC + '\f') * 6                                   # many chunks
        calls = []
        r = A.run_pipeline(long_doc, 6, 'medium', llm=fake_llm_factory(False, calls))
        self.assertTrue(r['meta']['truncated']); self.assertEqual(r['meta']['sections_used'], 3)
        self.assertGreater(r['meta']['sections_total'], 3)

    def test_shortfall_is_reported_not_faked(self):
        r = A.run_pipeline(DOC, 10, 'medium', llm=fake_llm_factory(True))
        self.assertEqual(len(r['questions']), 4)                      # only 4 distinct supportable questions exist
        self.assertEqual(r['meta']['shortfall'], 6)

    def test_topup_calls_are_bounded_and_avoid_existing(self):
        calls = []
        A.run_pipeline(DOC, 10, 'medium', llm=fake_llm_factory(True, calls))
        self.assertLessEqual(len(calls), A.MAX_LLM_CALLS)
        self.assertTrue(any(c['avoid'] for c in calls))

    def test_cancel(self):
        flag = {'n': 0}
        def cancelled():
            flag['n'] += 1
            return flag["n"] > 1
        with self.assertRaises(A.Cancelled):
            A.run_pipeline(DOC, 4, 'medium', cancelled=cancelled, llm=fake_llm_factory(False))

    def test_model_down_raises_when_nothing_generated(self):
        def dead(*a, **k): raise ConnectionError('connection refused')
        with self.assertRaises(ConnectionError):
            A.run_pipeline(DOC, 3, 'medium', llm=dead)
        self.assertIn('not reachable', A.friendly_error(ConnectionError('x')))

    def test_partial_failure_keeps_what_worked(self):
        state = {'n': 0}
        def flaky(chunk, count, diff, avoid):
            state['n'] += 1
            if state['n'] == 1: raise TimeoutError('slow')
            return fake_llm_factory(False)(chunk, count, diff, avoid)
        r = A.run_pipeline(DOC, 3, 'medium', llm=flaky)
        self.assertGreaterEqual(len(r['questions']), 1); self.assertTrue(r['meta']['notes'])

    def test_progress_callbacks(self):
        seen = []
        A.run_pipeline(DOC, 3, 'medium', progress=lambda **kw: seen.append(kw), llm=fake_llm_factory(False))
        self.assertTrue(any('Writing questions' in s.get('stage', '') for s in seen))
        self.assertEqual(seen[-1]['accepted'], 3)

    def test_too_little_text(self):
        with self.assertRaises(ValueError):
            A.run_pipeline('tiny', 3, 'medium', llm=fake_llm_factory(False))


class Http(unittest.TestCase):
    def setUp(self):
        self.client = A.app.test_client(); self.orig = A.call_llm; A.call_llm = fake_llm_factory(True)

    def tearDown(self):
        A.call_llm = self.orig; A.API_KEY = ''

    def test_sync_endpoint_contract_and_validation(self):
        r = self.client.post('/generate_mcq', json={'text': DOC, 'num_questions': 3, 'difficulty': 'easy'}); j = r.get_json()
        self.assertEqual(r.status_code, 200); self.assertTrue(j['success']); self.assertEqual(len(j['data']), 3)
        self.assertTrue({'question', 'options', 'answer', 'support', 'source_excerpt', 'warnings'} <= set(j['data'][0]))
        for body in ({}, {'text': DOC, 'num_questions': 'x'}, {'text': DOC, 'num_questions': 99},
                     {'text': DOC, 'num_questions': 3, 'difficulty': 'insane'}, {'text': 'short', 'num_questions': 3}):
            self.assertEqual(self.client.post('/generate_mcq', json=body).status_code, 400, body)
        self.assertEqual(self.client.post('/generate_mcq', data='nope').status_code, 400)

    def test_job_lifecycle(self):
        r = self.client.post('/jobs', json={'text': DOC, 'num_questions': 3, 'difficulty': 'medium'})
        self.assertEqual(r.status_code, 202); jid = r.get_json()['id']
        for _ in range(100):
            j = self.client.get('/jobs/' + jid).get_json()
            if j['status'] in ('done', 'error', 'cancelled'): break
            time.sleep(0.05)
        self.assertEqual(j['status'], 'done'); self.assertEqual(len(j['result']['questions']), 3)
        self.assertEqual(j['accepted'], 3)
        self.assertEqual(self.client.get('/jobs/doesnotexist').status_code, 404)

    def test_job_cancel(self):
        def slow(chunk, count, diff, avoid):
            time.sleep(0.3); return []
        A.call_llm = slow
        jid = self.client.post('/jobs', json={'text': DOC, 'num_questions': 3}).get_json()['id']
        time.sleep(0.1); self.assertTrue(self.client.delete('/jobs/' + jid).get_json()['success'])
        for _ in range(100):
            j = self.client.get('/jobs/' + jid).get_json()
            if j['status'] in ('done', 'error', 'cancelled'): break
            time.sleep(0.05)
        self.assertEqual(j['status'], 'cancelled'); self.assertNotIn('result', j)

    def test_job_error_message_is_friendly(self):
        def dead(*a, **k): raise ConnectionError('refused')
        A.call_llm = dead
        jid = self.client.post('/jobs', json={'text': DOC, 'num_questions': 3}).get_json()['id']
        for _ in range(100):
            j = self.client.get('/jobs/' + jid).get_json()
            if j['status'] in ('done', 'error'): break
            time.sleep(0.05)
        self.assertEqual(j['status'], 'error'); self.assertIn('Ollama', j['error']); self.assertNotIn('refused', j['error'])

    def test_api_key(self):
        A.API_KEY = 'secret'
        self.assertEqual(self.client.post('/generate_mcq', json={'text': DOC}).status_code, 401)
        self.assertEqual(self.client.post('/generate_mcq', json={'text': DOC, 'num_questions': 2},
                                          headers={'X-IGEN-KEY': 'wrong'}).status_code, 401)
        self.assertEqual(self.client.post('/generate_mcq', json={'text': DOC, 'num_questions': 2},
                                          headers={'X-IGEN-KEY': 'secret'}).status_code, 200)


if __name__ == '__main__':
    unittest.main(verbosity=1)
