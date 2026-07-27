/*
 * Exposes the ingest-form logic to admin/ingest.html's classic inline script.
 */

import { fileSizeError, MAX_UPLOAD_BYTES, panelsFor } from './ingest-form.js';

window.IngestForm = { panelsFor, fileSizeError, MAX_UPLOAD_BYTES };
