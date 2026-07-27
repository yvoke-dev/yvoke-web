/*
 * Exposes the job-status helpers to the classic inline script in admin/job-detail.html, which
 * keeps its Thymeleaf-injected page state (jobId, stepsOrder) and its polling loop.
 */

import { capitalize, getStatusClass, isStepCompleted, isTerminalStatus } from './job-status.js';

window.JobStatus = { capitalize, getStatusClass, isStepCompleted, isTerminalStatus };
