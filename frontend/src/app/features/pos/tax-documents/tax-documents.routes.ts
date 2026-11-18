import { Routes } from '@angular/router';
import { TaxDocuments } from './tax-documents';

/** Rutas de «Documentos tributarios» (cargadas de forma diferida desde `app.routes.ts` bajo `/pos/documentos-tributarios`). */
export const TAX_DOCUMENTS_ROUTES: Routes = [
  { path: '', component: TaxDocuments, title: 'Documentos tributarios' },
];
