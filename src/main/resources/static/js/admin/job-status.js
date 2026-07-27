/*
 * Ingestion-job status helpers for the job-detail page.
 *
 * `stepsOrder` is injected rather than read from page state: it comes from the server as
 * `${stepDbValues}`, so these functions are also a check on that contract — if the server's step
 * ordering and the page's expectations drift apart, the progress UI marks the wrong steps done.
 */

/**
 * Bootstrap-style badge class for a job status.
 *
 * 'cancelled' is muted, not danger: an operator stopping a job (or bulk-cancelling a repointed
 * connector's queue) is not a failure, and hundreds of red rows would hide the real ones.
 */
export function getStatusClass(status) {
    switch (String(status).toLowerCase()) {
        case 'queued': return 'badge-warning';
        case 'running': return 'badge-info';
        case 'completed': return 'badge-success';
        case 'failed': return 'badge-danger';
        case 'cancelled': return 'badge-muted';
        default: return 'badge-muted';
    }
}

/**
 * True once the job can no longer change state. Must mirror JobStatus.isTerminal() on the server:
 * a cancelled job never emits another progress event, so leaving it out keeps the SSE stream open
 * forever and the Stop button visible on a job nothing can stop.
 */
export function isTerminalStatus(status) {
    const s = String(status).toLowerCase();
    return s === 'completed' || s === 'failed' || s === 'cancelled';
}

/**
 * Whether `stepName` is finished, given the step the job is on and its overall status.
 *
 * A completed job has every step done; a failed one shows none as done (the failure point is
 * rendered separately). Otherwise a step is done once the job has moved past it — strictly before
 * the current step, so the in-flight step is not shown as finished.
 */
export function isStepCompleted(stepName, currentStep, status, stepsOrder) {
    const s = String(status).toLowerCase();
    if (s === 'completed') return true;
    if (s === 'failed') return false;

    const order = Array.isArray(stepsOrder) ? stepsOrder : [];
    const currentIndex = order.indexOf(currentStep);
    const stepIndex = order.indexOf(stepName);
    // An unknown step name yields -1 and is never "completed", which is the safe reading: a step
    // the server did not declare must not be painted as done.
    if (stepIndex === -1 || currentIndex === -1) return false;
    return stepIndex < currentIndex;
}

/** Capitalises a step/status name for display. */
export function capitalize(s) {
    if (!s) return '';
    return s.charAt(0).toUpperCase() + s.slice(1);
}
