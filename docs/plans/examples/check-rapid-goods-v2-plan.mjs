// Plan consistency checker only. This is not the application's evaluator or an AML classifier.
// Inputs represent facts already checked by the proposed services and human reviewers.
// Run: node docs/plans/examples/check-rapid-goods-v2-plan.mjs
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const specification = JSON.parse(readFileSync(
  new URL('./rapid-goods-v2-decision-cases.json', import.meta.url), 'utf8'))
const outcomes = new Set(['EXPLAINED', 'SUSPICIOUS', 'UNRESOLVED'])

function evaluateDesign(context, units) {
  const eligible = context.caseStatus === 'HOLD'
    && context.scopeEnumerated && context.adoptedFactsUsable
    && context.reviewBasisCurrent && context.policyApplicable
    && context.reviewerIndependent && context.otherScenarioGateSatisfied
    && units.length > 0 && units.every(unit => unit.assessmentValid)
  const taskGate = !context.hasOpenDecisionSupportEdd
    || (context.obligationTransferReady && context.continuationPlanReady)
  const followupReady = unit => !(unit.followupRequired || unit.outcome === 'UNRESOLVED'
    || unit.criticalUnknown) || context.continuationPlanReady
  const explained = unit => unit.outcome === 'EXPLAINED'
    && !unit.criticalUnknown && followupReady(unit)
  const evaluableForSuspicion = unit => {
    if (unit.outcome === 'EXPLAINED') return explained(unit)
    if (unit.outcome === 'SUSPICIOUS') {
      return unit.suspicionBasisComplete && followupReady(unit)
        && (!unit.criticalUnknown || unit.unresolvedDisclosed)
    }
    return unit.unresolvedDisclosed && followupReady(unit)
  }
  return {
    canExclude: Boolean(eligible && taskGate && units.every(explained)),
    canConfirm: Boolean(eligible && taskGate
      && units.some(unit => unit.outcome === 'SUSPICIOUS' && unit.suspicionBasisComplete)
      && units.every(evaluableForSuspicion)),
  }
}

const ids = new Set()
let invariantChecks = 0
let excludePositiveCases = 0
let confirmPositiveCases = 0
for (const sample of specification.cases) {
  assert(!ids.has(sample.id), `Duplicate ID: ${sample.id}`)
  ids.add(sample.id)
  const context = { ...specification.contextDefaults, ...sample.context }
  for (const key of Object.keys(sample.context ?? {})) {
    assert(Object.hasOwn(specification.contextDefaults, key), `${sample.id}: unknown context ${key}`)
  }
  const units = sample.units.map(unit => {
    assert(outcomes.has(unit.outcome), `${sample.id}: unsupported outcome`)
    for (const key of Object.keys(unit)) {
      assert(key === 'outcome' || Object.hasOwn(specification.unitDefaults, key),
        `${sample.id}: unknown unit field ${key}`)
    }
    return { ...specification.unitDefaults, ...unit }
  })
  const actual = evaluateDesign(context, units)
  assert.deepEqual(actual, sample.expected, `${sample.id}: ${sample.name}`)
  assert(!(actual.canExclude && actual.canConfirm), `${sample.id}: incompatible final directions`)
  invariantChecks++
  assert.deepEqual(evaluateDesign(context, [...units].reverse()), actual,
    `${sample.id}: ordering must not change the result`)
  invariantChecks++
  for (const guard of ['scopeEnumerated', 'adoptedFactsUsable', 'reviewBasisCurrent',
    'policyApplicable', 'reviewerIndependent', 'otherScenarioGateSatisfied']) {
    assert.deepEqual(evaluateDesign({ ...context, [guard]: false }, units),
      { canExclude: false, canConfirm: false }, `${sample.id}: guard ${guard}`)
    invariantChecks++
  }
  if (actual.canExclude) excludePositiveCases++
  if (actual.canConfirm) confirmPositiveCases++
}
// Reject-all / accept-all policies must not appear to pass this design suite.
assert(excludePositiveCases > 0 && confirmPositiveCases > 0)
assert(specification.cases.some(sample => !sample.expected.canExclude && !sample.expected.canConfirm))
console.log(JSON.stringify({
  result: 'PASS',
  designCases: ids.size,
  invariantChecks,
  excludePositiveCases,
  confirmPositiveCases,
  scope: 'Design-table consistency only; not production, source-verification, authorization or transaction acceptance.',
}, null, 2))
