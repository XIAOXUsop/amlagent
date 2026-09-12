import { array, boolean, number, object, record, string, type FieldRule } from './contract-runtime'
import { createCaseDossierSchema } from './dossier-response-schema'
import { agentEvalDatasetSchema, issueReviewSchema } from './specialized-response-schemas'

const nullableString = string({ nullable: true })
const nullableNumber = number({ nullable: true })
const strings = array(string())

const workflowEvent = object({
  caseId: number(),
  stage: string(),
  content: string(),
})

const dueDiligenceReport = object({
  customerId: string(),
  customerName: string(),
  riskLevel: string(),
  transactionProfile: string(),
  corporateProfile: string(),
  sanctions: strings,
  legalBasis: strings,
  riskPoints: strings,
  conclusion: string(),
  evidenceChain: strings,
  manualReviewRequired: boolean(),
  findingCodes: strings,
  actionCodes: strings,
})

const caseItem = object({
  id: number(),
  customerId: string(),
  customerName: string(),
  alertRule: string(),
  status: string(),
  riskLevel: nullableString,
  rawRiskLevel: nullableString,
  reportJson: nullableString,
  summary: nullableString,
  reportSource: nullableString,
  snapshotId: nullableString,
  modelProvider: nullableString,
  modelName: nullableString,
  modelFallback: boolean(),
  executionVersion: number(),
  reviewRevision: number(),
  investigationContractVersion: number(),
  reviewDisposition: nullableString,
  reviewReasonCode: nullableString,
  reviewedAt: nullableString,
  retryCount: number(),
  failureCode: nullableString,
  failureMessage: nullableString,
  createdAt: string(),
  updatedAt: string(),
})

const customer = object({
  id: string(),
  name: string(),
  type: string(),
  industry: string(),
  region: string(),
  regCapital: string(),
})

const customerAdmin = object({
  id: number(),
  customerNo: string(),
  name: string(),
  idCardMasked: string(),
  type: nullableString,
  industry: nullableString,
  region: nullableString,
  regCapital: nullableString,
  status: string(),
  createdAt: string(),
  updatedAt: string(),
})

const page = (item: FieldRule): FieldRule =>
  object({ content: array(item), totalElements: number(), totalPages: number(), number: number(), size: number() })

const operation = object({
  caseId: number(),
  customerId: string(),
  customerName: string(),
  caseStatus: string(),
  priority: string({ enumValues: ['CRITICAL', 'HIGH', 'MEDIUM', 'NORMAL'] }),
  priorityScore: number(),
  priorityReasons: strings,
  priorityPolicy: string(),
  phase: string({ enumValues: ['INVESTIGATION', 'REVIEW', 'ENHANCED_DUE_DILIGENCE', 'REPORTING', 'COMPLETED'] }),
  responsibleRole: string(),
  assignedTo: nullableString,
  assignedUnit: nullableString,
  clockStartedAt: string(),
  dueAt: nullableString,
  overdue: boolean(),
  minutesRemaining: number(),
  slaPolicy: string(),
  calculatedAt: string(),
})

const alert = object({
  id: number(),
  externalAlertId: string(),
  customerId: string(),
  ruleCode: string(),
  scenarioCode: string(),
  hitReason: string(),
  occurredAt: string(),
  status: string({ enumValues: ['NEW', 'LINKED', 'DUPLICATE'] }),
  caseId: nullableNumber,
  revision: number(),
  resolutionReason: nullableString,
  createdBy: string(),
  createdAt: string(),
  updatedAt: string(),
})

const evidence = object({
  id: number(),
  hypothesisId: number(),
  evidenceType: string(),
  evidenceReference: string(),
  stance: string({ enumValues: ['SUPPORTS', 'CONTRADICTS'] }),
  findingSummary: string(),
  createdBy: string(),
  createdAt: string(),
})

const hypothesis = object({
  id: number(),
  caseId: number(),
  scenarioCode: string(),
  hypothesisCode: string(),
  title: string(),
  investigationQuestion: string(),
  requiredEvidenceTypes: strings,
  status: string({ enumValues: ['OPEN', 'CONFIRMED', 'REJECTED'] }),
  rationale: nullableString,
  revision: number(),
  createdBy: string(),
  updatedBy: nullableString,
  createdAt: string(),
  updatedAt: string(),
  evidence: array(evidence),
})

const coverage = object({
  id: number(),
  alertId: number(),
  caseId: number(),
  hypothesisId: nullableNumber,
  hypothesisRevision: nullableNumber,
  conclusion: string({ enumValues: ['PENDING', 'SUSPICIOUS', 'EXPLAINED'] }),
  analysisSummary: nullableString,
  revision: number(),
  updatedBy: nullableString,
  updatedAt: string(),
})

const investigation = object({
  contractVersion: number(),
  alerts: array(alert),
  hypotheses: array(hypothesis),
  coverage: array(coverage),
  readyForFinalReview: boolean(),
  generalBlockers: strings,
  confirmSuspiciousBlockers: strings,
  excludeFalsePositiveBlockers: strings,
})

const assistantConversation = object({
  id: string(),
  customerId: number(),
  customerNo: string(),
  status: string({ enumValues: ['ACTIVE', 'ARCHIVED', 'EXPIRED'] }),
  createdAt: string(),
  updatedAt: string(),
  expiresAt: string(),
})

const assistantMessage = object({
  id: string(),
  sequenceNo: number(),
  role: string({ enumValues: ['USER', 'ASSISTANT'] }),
  status: string({ enumValues: ['ACCEPTED', 'PROCESSING', 'COMPLETED', 'REFUSED', 'FAILED', 'BLOCKED'] }),
  resultType: nullableString,
  content: string(),
  createdAt: string(),
  completedAt: nullableString,
})

const manualReview = object({
  id: number(),
  caseId: number(),
  reviewerId: string(),
  agentRiskLevel: string(),
  guardrailRiskLevel: string(),
  reviewerRiskLevel: string(),
  decision: string(),
  reasonCode: string(),
  comment: string(),
  reviewRevision: number(),
  caseStatusBefore: string(),
  caseStatusAfter: string(),
  createdAt: string(),
  completedAt: string(),
})

const eddEvidence = object({
  id: number(),
  evidenceId: string(),
  requiredItemCode: string(),
  sourceSystem: string(),
  sourceReference: string(),
  contentSha256: string(),
  capturedBy: string(),
  capturedAt: string(),
})

const edd = object({
  id: number(),
  caseId: number(),
  roundNo: number(),
  reasonCode: string(),
  requiredItems: strings,
  requestedBy: string(),
  requestedAt: string(),
  assignedTo: nullableString,
  assignedUnit: nullableString,
  dueAt: string(),
  status: string({ enumValues: ['OPEN', 'SUBMITTED', 'RESOLVED', 'CANCELLED'] }),
  overdue: boolean(),
  revision: number(),
  responseSummary: nullableString,
  evidenceReferences: strings,
  respondedBy: nullableString,
  respondedAt: nullableString,
  resolvedAt: nullableString,
  cancelledBy: nullableString,
  cancelledAt: nullableString,
  cancellationReason: nullableString,
  evidenceItems: array(eddEvidence),
})

const suspiciousReport = object({
  id: number(),
  caseId: number(),
  reviewId: number(),
  status: string({ enumValues: ['PENDING_SUBMISSION', 'SUBMITTED', 'RETURNED_FOR_CORRECTION'] }),
  reportReason: string(),
  createdBy: string(),
  revision: number(),
  externalReference: nullableString,
  submittedBy: nullableString,
  submittedAt: nullableString,
  returnedBy: nullableString,
  returnedAt: nullableString,
  returnReason: nullableString,
  createdAt: string(),
  updatedAt: string(),
})

const explanationUnit = object({
  unitId: number(),
  alertId: number(),
  externalAlertId: nullableString,
  hypothesisId: nullableNumber,
  policyCode: nullableString,
  draftRevision: number(),
  draftJson: nullableString,
  hasCurrentSubmission: boolean(),
  currentSubmissionId: nullableNumber,
  currentOutcome: string({ nullable: true, enumValues: ['EXPLAINED', 'SUSPICIOUS', 'UNRESOLVED'] }),
  criticalUnknown: boolean(),
  followupRequired: boolean(),
  blockers: strings,
})

const explanationIssue = object({
  issueId: number(),
  unitId: nullableNumber,
  issueKey: string(),
  severity: string(),
  questionCode: nullableString,
  transactionIds: nullableString,
  description: string(),
  disposition: string(),
  dispositionReason: nullableString,
  resolvedBy: nullableString,
  confirmedBy: nullableString,
  revision: number(),
})

const explanationEvidence = object({
  artifactVersionId: number(),
  artifactKey: string(),
  version: number(),
  sourceSystem: string(),
  sourceReference: string(),
  contentSha256: string(),
  claimedSha256: nullableString,
  availability: string(),
  integrityStatus: string(),
  capturedBy: string(),
  capturedAt: string(),
})

const explanationClaimLink = object({
  linkId: number(),
  artifactVersionId: number(),
  direction: string(),
  sourceFamily: string(),
  location: nullableString,
  note: nullableString,
})

const explanationClaim = object({
  claimId: number(),
  claimCode: string(),
  status: string(),
  importance: string(),
  judgement: nullableString,
  methodNote: nullableString,
  limitations: nullableString,
  notApplicableReason: nullableString,
  claimRevision: number(),
  updatedBy: nullableString,
  links: array(explanationClaimLink),
  independentSourceCount: number(),
})

const explanationWorkspace = object({
  caseId: number(),
  contractVersion: number(),
  caseFactsEpoch: number(),
  stale: boolean(),
  units: array(explanationUnit),
  issues: array(explanationIssue),
  artifacts: array(explanationEvidence),
  claims: array(explanationClaim),
  generalBlockers: strings,
  canExclude: boolean(),
  canConfirm: boolean(),
  confirmBlockers: strings,
  excludeBlockers: strings,
})

const toolTrace = object({
  executionVersion: number(),
  sequenceNo: number(),
  toolName: string(),
  success: boolean(),
  argumentValid: boolean(),
  durationMs: number(),
  resultDigest: nullableString,
  errorCode: nullableString,
})

const issueReview = issueReviewSchema
const caseDossier = createCaseDossierSchema()
const agentEvalDataset = agentEvalDatasetSchema
const apiError = object({
  code: string(),
  message: string(),
  traceId: string(),
  timestamp: string(),
  fieldErrors: record(string({ semanticValidation: false }), { optional: true }),
  conflict: object(
    {
      type: string(),
      id: number({ nullable: true }),
      currentVersion: number({ nullable: true }),
    },
    { optional: true },
  ),
})

export const responseSchemas = {
  nullableString,
  nullableNumber,
  strings,
  workflowEvent,
  dueDiligenceReport,
  caseItem,
  customer,
  customerAdmin,
  page,
  operation,
  alert,
  evidence,
  hypothesis,
  coverage,
  investigation,
  assistantConversation,
  assistantMessage,
  manualReview,
  eddEvidence,
  edd,
  suspiciousReport,
  explanationUnit,
  explanationIssue,
  explanationEvidence,
  explanationClaimLink,
  explanationClaim,
  explanationWorkspace,
  toolTrace,
  issueReview,
  caseDossier,
  agentEvalDataset,
  apiError,
}
