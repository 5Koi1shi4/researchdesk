import type { components } from '../src/generated/contracts';

type Schemas = components['schemas'];
const valid: Schemas['GenerationRequest'] = {kind: 'ANSWER', projectId: 'p1', documentVersionIds: ['v1'], prompt: 'Summarize'};
const citation: Schemas['Citation'] = {chunkId: 'c1', documentVersionId: 'v1', title: 'Paper', page: null, paragraph: 2, excerpt: null, availability: 'AVAILABLE'};
const event: Schemas['RunEvent'] = {runId: 'r1', seq: 1, type: 'citation', payload: {citation}};
// @ts-expect-error Unknown kind must not become an open string.
const badKind: Schemas['RunKind'] = 'UNKNOWN';
// @ts-expect-error Task success and run completion have distinct vocabularies.
const badRun: Schemas['RunState'] = 'SUCCEEDED';
// @ts-expect-error Unknown task states must fail.
const badTask: Schemas['TaskState'] = 'UNKNOWN';
// @ts-expect-error Optional conversationId may be omitted, but supplied null is invalid.
const nullConversation: Schemas['GenerationRequest'] = {kind: 'ANSWER', projectId: 'p1', documentVersionIds: ['v1'], prompt: 'Summarize', conversationId: null};
// @ts-expect-error Delta payload requires text.
const badEvent: Schemas['RunEvent'] = {runId: 'r1', seq: 1, type: 'delta', payload: {state: 'COMPLETED'}};
void [valid, event, badKind, badRun, badTask, nullConversation, badEvent];
