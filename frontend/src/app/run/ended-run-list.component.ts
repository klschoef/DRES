import { Component, OnInit } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Observable } from 'rxjs';
import { take } from 'rxjs/operators';
import { ApiEndedEvaluationInfo, DownloadService, EvaluationAdministratorService } from '../../../openapi';
import {
  ConfirmationDialogComponent,
  ConfirmationDialogComponentData,
} from '../shared/confirmation-dialog/confirmation-dialog.component';

/**
 * Lists evaluations that are no longer running and lets administrators download or permanently delete them.
 */
@Component({
  selector: 'app-ended-run-list',
  templateUrl: './ended-run-list.component.html',
  styleUrls: ['./ended-run-list.component.scss'],
  standalone: false,
})
export class EndedRunListComponent implements OnInit {
  displayedColumns = ['actions', 'name', 'template', 'type', 'started', 'ended', 'tasks', 'submissions', 'id'];

  runs: ApiEndedEvaluationInfo[] = [];
  loading = false;
  /** IDs of the evaluations that are currently being deleted. */
  deleting = new Set<string>();

  constructor(
    private runAdminService: EvaluationAdministratorService,
    private downloadService: DownloadService,
    private snackBar: MatSnackBar,
    private dialog: MatDialog
  ) {}

  ngOnInit(): void {
    this.refresh();
  }

  refresh() {
    this.loading = true;
    this.runAdminService.getApiV2EvaluationAdminEndedList().subscribe({
      next: (runs) => {
        this.runs = runs;
        this.loading = false;
      },
      error: (r) => {
        this.loading = false;
        this.snackBar.open(`Error: ${r?.error?.description ?? r.message}`, null, { duration: 5000 });
      },
    });
  }

  delete(run: ApiEndedEvaluationInfo) {
    const dialogRef = this.dialog.open(ConfirmationDialogComponent, {
      data: {
        text:
          `Permanently delete the evaluation "${run.name}" and its ${run.submissionCount} submissions? ` +
          `Scores and submissions of this evaluation cannot be recovered. The evaluation template is kept.`,
        color: 'warn',
      } as ConfirmationDialogComponentData,
    });
    dialogRef.afterClosed().subscribe((confirmed) => {
      if (!confirmed) {
        return;
      }
      this.deleting.add(run.evaluationId);
      this.runAdminService.deleteApiV2EvaluationAdminByEvaluationId(run.evaluationId).subscribe({
        next: (r) => {
          this.deleting.delete(run.evaluationId);
          this.snackBar.open(`Success: ${r.description}`, null, { duration: 5000 });
          this.refresh();
        },
        error: (r) => {
          this.deleting.delete(run.evaluationId);
          this.snackBar.open(`Error: ${r?.error?.description ?? r.message}`, null, { duration: 5000 });
          this.refresh();
        },
      });
    });
  }

  private downloads = new Map<string, Observable<any>>();
  private fileNames = new Map<string, () => string>();

  /** Cached per evaluation, so change detection does not create a new request observable on every cycle. */
  downloadProvider(runId: string): Observable<any> {
    if (!this.downloads.has(runId)) {
      this.downloads.set(runId, this.downloadService.getApiV2DownloadEvaluationByEvaluationId(runId).pipe(take(1)));
    }
    return this.downloads.get(runId);
  }

  fileProvider(name: string): () => string {
    if (!this.fileNames.has(name)) {
      this.fileNames.set(name, () => `${name}.json`);
    }
    return this.fileNames.get(name);
  }

  trackById(_: number, run: ApiEndedEvaluationInfo) {
    return run.evaluationId;
  }
}
