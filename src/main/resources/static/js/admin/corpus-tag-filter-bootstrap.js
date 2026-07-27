/*
 * Exposes the tag-scoping rule to the classic inline script in admin/documents.html, which cannot
 * be a module: its runtime-cloned rows carry inline on*= handlers that must resolve globally.
 */

import { tagOptionsFor } from './corpus-tag-filter.js';

window.CorpusTagFilter = { tagOptionsFor };
