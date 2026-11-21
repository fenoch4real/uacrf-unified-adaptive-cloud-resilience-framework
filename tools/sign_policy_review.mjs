#!/usr/bin/env node
import { createPrivateKey, sign } from 'node:crypto';
import { closeSync, constants, fstatSync, openSync, readFileSync } from 'node:fs';

const names = ['key', 'kid', 'tenant', 'environment', 'service', 'policy', 'revision', 'digest', 'reviewer'];
const identifier = /^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$/;

function options(args) {
  const values = {};
  for (let i = 0; i < args.length; i += 2) {
    const name = args[i]?.startsWith('--') ? args[i].slice(2) : '';
    if (!names.includes(name) || Object.hasOwn(values, name) || !args[i + 1]) {
      throw new Error('Provide each required option exactly once with a value.');
    }
    values[name] = args[i + 1];
  }
  if (names.some((name) => !Object.hasOwn(values, name))) {
    throw new Error(`Required options: ${names.map((name) => `--${name}`).join(' ')}.`);
  }
  for (const name of ['kid', 'tenant', 'environment', 'service', 'policy', 'reviewer']) {
    if (!identifier.test(values[name])) throw new Error(`Invalid ${name}.`);
  }
  if (!/^[1-9][0-9]*$/.test(values.revision) || !Number.isSafeInteger(Number(values.revision))) {
    throw new Error('Revision must be a positive safe integer.');
  }
  if (!/^[0-9a-f]{64}$/.test(values.digest)) throw new Error('Digest must be 64 lowercase hexadecimal characters.');
  return values;
}

async function reasonFromStdin() {
  const chunks = [];
  let length = 0;
  for await (const chunk of process.stdin) {
    length += chunk.length;
    if (length > 8000) throw new Error('Reason exceeds the size limit.');
    chunks.push(chunk);
  }
  const reason = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(Buffer.concat(chunks));
  if (!reason.trim() || reason.length > 2000 || reason.includes('\0')) {
    throw new Error('Reason must contain 1 to 2000 characters and no NUL characters.');
  }
  return reason;
}

async function main() {
  const value = options(process.argv.slice(2));
  const reason = await reasonFromStdin();
  const file = openSync(value.key, constants.O_RDONLY | (constants.O_NOFOLLOW ?? 0));
  let key;
  try {
    const keyInfo = fstatSync(file);
    if (!keyInfo.isFile()) throw new Error('The key path must identify a regular file.');
    if (keyInfo.size < 1 || keyInfo.size > 65536) throw new Error('Private key file size is invalid.');
    if (process.platform !== 'win32' && (keyInfo.mode & 0o077) !== 0) {
      throw new Error('Private key file must not be accessible to group or other users.');
    }
    key = createPrivateKey(readFileSync(file));
  } finally {
    closeSync(file);
  }
  const bits = key.asymmetricKeyDetails?.modulusLength;
  if (key.asymmetricKeyType !== 'rsa' || !Number.isInteger(bits) || bits < 2048 || bits > 8192) {
    throw new Error('The private key must be RSA with 2048 to 8192 bits.');
  }
  const header = { alg: 'RS256', kid: value.kid, typ: 'uacrf.policy-review+jws' };
  const claims = {
    decision: 'APPROVE',
    environment_id: value.environment,
    policy_digest: value.digest,
    policy_id: value.policy,
    policy_revision: Number(value.revision),
    reason,
    reviewer_id: value.reviewer,
    service_id: value.service,
    tenant_id: value.tenant,
  };
  const input = `${Buffer.from(JSON.stringify(header)).toString('base64url')}.${Buffer.from(JSON.stringify(claims)).toString('base64url')}`;
  const signature = sign('RSA-SHA256', Buffer.from(input, 'ascii'), key).toString('base64url');
  process.stdout.write(`${input}.${signature}\n`);
}

main().catch((error) => {
  console.error(`Policy signing failed: ${error.message}`);
  process.exitCode = 1;
});
