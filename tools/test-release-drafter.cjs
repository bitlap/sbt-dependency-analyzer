const { test } = require('node:test');
const assert = require('node:assert/strict');
const check = require('../.github/scripts/release-status.cjs');

function fixture(response) {
  const outputs = {};
  const calls = [];
  return {
    outputs, calls,
    input: {
      version: '0.12.0',
      context: { repo: { owner: 'bitlap', repo: 'sbt-dependency-analyzer' } },
      core: { setOutput: (k, v) => { outputs[k] = v; }, info: () => {} },
      github: { rest: { repos: { getReleaseByTag: async args => {
        calls.push(args);
        if (response instanceof Error) throw response;
        return { data: response };
      } } } },
    },
  };
}

test('an unpublished project version is eligible for a draft', async () => {
  const f = fixture(Object.assign(new Error('missing'), { status: 404 }));
  await check(f.input);
  assert.equal(f.outputs['should-draft'], 'true');
  assert.deepEqual(f.calls, [{ owner: 'bitlap', repo: 'sbt-dependency-analyzer', tag: 'v0.12.0' }]);
});
test('an existing draft may be updated or explicitly published', async () => {
  const f = fixture({ draft: true });
  await check(f.input);
  assert.equal(f.outputs['should-draft'], 'true');
});
test('an already published version is left untouched', async () => {
  const f = fixture({ draft: false });
  await check(f.input);
  assert.equal(f.outputs['should-draft'], 'false');
});
test('permission, rate limit and network errors do not create a new release', async () => {
  for (const status of [403, 429, undefined]) {
    const error = Object.assign(new Error('API failed'), { status });
    const f = fixture(error);
    await assert.rejects(check(f.input), e => e === error);
    assert.deepEqual(f.outputs, {});
  }
});
test('malformed versions are rejected before any API request', async () => {
  for (const version of ['', 'v0.12.0', '0.12.0\nother', '0.12.0/other', '0.12.0\n']) {
    const f = fixture({ draft: false });
    f.input.version = version;
    await assert.rejects(check(f.input), /Invalid project release version/);
    assert.deepEqual(f.calls, []);
  }
});
