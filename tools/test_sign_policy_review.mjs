import { generateKeyPairSync, verify } from 'node:crypto';
import { chmodSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import test from 'node:test';
import assert from 'node:assert/strict';

test('signs the exact canonical claims and rejects invalid inputs', () => {
  const directory = mkdtempSync(join(tmpdir(), 'uacrf-policy-sign-test-'));
  try {
    const keys = generateKeyPairSync('rsa', { modulusLength: 2048 });
    const keyFile = join(directory, 'reviewer.pem');
    writeFileSync(keyFile, keys.privateKey.export({ type: 'pkcs8', format: 'pem' }), { mode: 0o600 });
    const args = ['tools/sign_policy_review.mjs', '--key', keyFile, '--kid', 'key-1',
      '--tenant', 'tenant', '--environment', 'environment', '--service', 'service',
      '--policy', 'policy', '--revision', '3', '--digest', 'a'.repeat(64), '--reviewer', 'reviewer'];
    const result = spawnSync(process.execPath, args, { input: 'Approved: café', encoding: 'utf8' });
    assert.equal(result.status, 0, result.stderr);
    const parts = result.stdout.trim().split('.');
    assert.equal(parts.length, 3);
    assert.equal(verify('RSA-SHA256', Buffer.from(`${parts[0]}.${parts[1]}`), keys.publicKey,
      Buffer.from(parts[2], 'base64url')), true);
    assert.equal(Buffer.from(parts[1], 'base64url').toString('utf8'),
      `{"decision":"APPROVE","environment_id":"environment","policy_digest":"${'a'.repeat(64)}",`
      + '"policy_id":"policy","policy_revision":3,"reason":"Approved: café","reviewer_id":"reviewer",'
      + '"service_id":"service","tenant_id":"tenant"}');
    const duplicate = spawnSync(process.execPath, [...args, '--policy', 'other'], { input: 'reason', encoding: 'utf8' });
    assert.notEqual(duplicate.status, 0);
    assert.equal(duplicate.stdout, '');
    const blank = spawnSync(process.execPath, args, { input: '  ', encoding: 'utf8' });
    assert.notEqual(blank.status, 0);
    assert.equal(blank.stdout, '');
    const invalidUtf8 = spawnSync(process.execPath, args, { input: Buffer.from([0xff]), encoding: 'utf8' });
    assert.notEqual(invalidUtf8.status, 0);
    assert.equal(invalidUtf8.stdout, '');
    const badRevision = [...args];
    badRevision[badRevision.indexOf('--revision') + 1] = '9007199254740992';
    const overflow = spawnSync(process.execPath, badRevision, { input: 'reason', encoding: 'utf8' });
    assert.notEqual(overflow.status, 0);
    assert.equal(overflow.stdout, '');
    if (process.platform !== 'win32') {
      chmodSync(keyFile, 0o644);
      const exposed = spawnSync(process.execPath, args, { input: 'reason', encoding: 'utf8' });
      assert.notEqual(exposed.status, 0);
      assert.equal(exposed.stdout, '');
    }
  } finally {
    rmSync(directory, { recursive: true });
  }
});
