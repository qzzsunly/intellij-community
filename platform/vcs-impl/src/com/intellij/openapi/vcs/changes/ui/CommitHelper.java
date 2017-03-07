/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intellij.openapi.vcs.changes.ui;

import com.intellij.history.LocalHistory;
import com.intellij.history.LocalHistoryAction;
import com.intellij.ide.util.DelegatingProgressIndicator;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vcs.*;
import com.intellij.openapi.vcs.changes.*;
import com.intellij.openapi.vcs.changes.actions.MoveChangesToAnotherListAction;
import com.intellij.openapi.vcs.changes.committed.CommittedChangesCache;
import com.intellij.openapi.vcs.checkin.CheckinEnvironment;
import com.intellij.openapi.vcs.checkin.CheckinHandler;
import com.intellij.openapi.vcs.update.RefreshVFsSynchronously;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.NullableFunction;
import com.intellij.util.concurrency.Semaphore;
import org.jetbrains.annotations.CalledInAwt;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;

import static com.intellij.openapi.application.ApplicationManager.getApplication;
import static com.intellij.openapi.ui.Messages.getQuestionIcon;
import static com.intellij.openapi.util.text.StringUtil.*;
import static com.intellij.openapi.vcs.VcsBundle.message;
import static com.intellij.openapi.vcs.changes.ChangesUtil.processChangesByVcs;
import static com.intellij.util.WaitForProgressToShow.runOrInvokeLaterAboveProgress;
import static com.intellij.util.containers.ContainerUtil.newArrayList;
import static com.intellij.util.containers.ContainerUtil.newHashSet;
import static com.intellij.util.ui.ConfirmationDialog.requestForConfirmation;
import static java.util.Collections.emptyList;

public class CommitHelper {
  public static final Key<Object> DOCUMENT_BEING_COMMITTED_KEY = new Key<>("DOCUMENT_BEING_COMMITTED");

  private final static Logger LOG = Logger.getInstance("#com.intellij.openapi.vcs.changes.ui.CommitHelper");
  @NotNull private final Project myProject;

  @NotNull private final ChangeList myChangeList;
  @NotNull private final List<Change> myIncludedChanges;

  @NotNull private final String myActionName;
  @NotNull private final String myCommitMessage;

  @NotNull private final List<CheckinHandler> myHandlers;
  private final boolean myAllOfDefaultChangeListChangesIncluded;
  private final boolean myForceSyncCommit;
  @NotNull private final NullableFunction<Object, Object> myAdditionalData;
  @Nullable private final CommitResultHandler myCustomResultHandler;
  @NotNull private final List<Document> myCommittingDocuments = newArrayList();
  @NotNull private final VcsConfiguration myConfiguration;
  @NotNull private final HashSet<String> myFeedback = newHashSet();

  public CommitHelper(@NotNull Project project,
                      @NotNull ChangeList changeList,
                      @NotNull List<Change> includedChanges,
                      @NotNull String actionName,
                      @NotNull String commitMessage,
                      @NotNull List<CheckinHandler> handlers,
                      boolean allOfDefaultChangeListChangesIncluded,
                      boolean synchronously,
                      @NotNull NullableFunction<Object, Object> additionalDataHolder,
                      @Nullable CommitResultHandler customResultHandler) {
    myProject = project;
    myChangeList = changeList;
    myIncludedChanges = includedChanges;
    myActionName = actionName;
    myCommitMessage = commitMessage;
    myHandlers = handlers;
    myAllOfDefaultChangeListChangesIncluded = allOfDefaultChangeListChangesIncluded;
    myForceSyncCommit = synchronously;
    myAdditionalData = additionalDataHolder;
    myCustomResultHandler = customResultHandler;
    myConfiguration = VcsConfiguration.getInstance(myProject);
  }

  public boolean doCommit() {
    return doCommit((AbstractVcs)null);
  }

  public boolean doCommit(@Nullable AbstractVcs vcs) {
    return doCommit(new CommitProcessor(vcs));
  }

  public boolean doAlienCommit(@NotNull AbstractVcs vcs) {
    return doCommit(new AlienCommitProcessor(vcs));
  }

  private boolean doCommit(GeneralCommitProcessor processor) {
    Task.Backgroundable task = new Task.Backgroundable(myProject, myActionName, true, myConfiguration.getCommitOption()) {
      public void run(@NotNull ProgressIndicator indicator) {
        ProjectLevelVcsManager vcsManager = ProjectLevelVcsManager.getInstance(myProject);
        vcsManager.startBackgroundVcsOperation();
        try {
          delegateCommitToVcsThread(processor);
        }
        finally {
          vcsManager.stopBackgroundVcsOperation();
        }
      }

      @Override
      public boolean shouldStartInBackground() {
        return !myForceSyncCommit && super.shouldStartInBackground();
      }

      @Override
      public boolean isConditionalModal() {
        return myForceSyncCommit;
      }
    };
    ProgressManager.getInstance().run(task);
    return doesntContainErrors(processor.getVcsExceptions());
  }

  private void delegateCommitToVcsThread(GeneralCommitProcessor processor) {
    ProgressIndicator indicator = new DelegatingProgressIndicator();
    Semaphore endSemaphore = new Semaphore();

    endSemaphore.down();
    ChangeListManagerImpl.getInstanceImpl(myProject).executeOnUpdaterThread(() -> {
      indicator.setText("Performing VCS commit...");
      try {
        ProgressManager.getInstance().runProcess(() -> {
          indicator.checkCanceled();
          generalCommit(processor);
        }, indicator);
      }
      finally {
        endSemaphore.up();
      }
    });

    indicator.setText("Waiting for VCS background tasks to finish...");
    while (!endSemaphore.waitFor(20)) {
      indicator.checkCanceled();
    }
  }

  private void reportResult(@NotNull GeneralCommitProcessor processor) {
    List<VcsException> errors = collectErrors(processor.getVcsExceptions());
    int errorsSize = errors.size();
    int warningsSize = processor.getVcsExceptions().size() - errorsSize;

    VcsNotifier notifier = VcsNotifier.getInstance(myProject);
    String message = getCommitSummary(processor);
    if (errorsSize > 0) {
      String title = pluralize(message("message.text.commit.failed.with.error"), errorsSize);
      notifier.notifyError(title, message);
    }
    else if (warningsSize > 0) {
      String title = pluralize(message("message.text.commit.finished.with.warning"), warningsSize);
      notifier.notifyImportantWarning(title, message);
    }
    else {
      notifier.notifySuccess(message);
    }
  }

  @NotNull
  private String getCommitSummary(@NotNull GeneralCommitProcessor processor) {
    StringBuilder content = new StringBuilder(getFileSummaryReport(processor.getChangesFailedToCommit()));
    if (!isEmpty(myCommitMessage)) {
      content.append(": ").append(escape(myCommitMessage));
    }
    if (!myFeedback.isEmpty()) {
      content.append("<br/>");
      content.append(join(myFeedback, "<br/>"));
    }
    List<VcsException> exceptions = processor.getVcsExceptions();
    if (!doesntContainErrors(exceptions)) {
      content.append("<br/>");
      content.append(join(exceptions, Throwable::getMessage, "<br/>"));
    }
    return content.toString();
  }

  @NotNull
  private String getFileSummaryReport(@NotNull List<Change> changesFailedToCommit) {
    int failed = changesFailedToCommit.size();
    int committed = myIncludedChanges.size() - failed;
    String fileSummary = committed + " " + pluralize("file", committed) + " committed";
    if (failed > 0) {
      fileSummary += ", " + failed + " " + pluralize("file", failed) + " failed to commit";
    }
    return fileSummary;
  }

  /*
    Commit message is passed to NotificationManagerImpl#doNotify and displayed as HTML.
    Thus HTML tag braces (< and >) should be escaped,
    but only they since the text is passed directly to HTML <BODY> tag and is not a part of an attribute or else.
   */
  private static String escape(String s) {
    String[] FROM = {"<", ">"};
    String[] TO = {"&lt;", "&gt;"};
    return replace(s, FROM, TO);
  }

  private static boolean doesntContainErrors(List<VcsException> vcsExceptions) {
    for (VcsException vcsException : vcsExceptions) {
      if (!vcsException.isWarning()) return false;
    }
    return true;
  }

  private void generalCommit(GeneralCommitProcessor processor) {
    try {
      ReadAction.run(() -> markCommittingDocuments());
      try {
        processor.callSelf();
      }
      finally {
        ReadAction.run(() -> unmarkCommittingDocuments());
      }

      processor.doBeforeRefresh();
    }
    catch (ProcessCanceledException pce) {
      throw pce;
    }
    catch (RuntimeException e) {
      LOG.error(e);
      processor.myVcsExceptions.add(new VcsException(e));
      throw e;
    }
    catch (Throwable e) {
      LOG.error(e);
      processor.myVcsExceptions.add(new VcsException(e));
      throw new RuntimeException(e);
    }
    finally {
      commitCompleted(processor.getVcsExceptions(), processor);
      processor.customRefresh();
      runOrInvokeLaterAboveProgress(() -> processor.doPostRefresh(), null, myProject);
    }
  }

  private class AlienCommitProcessor extends GeneralCommitProcessor {
    @NotNull private final AbstractVcs myVcs;

    private AlienCommitProcessor(@NotNull AbstractVcs vcs) {
      myVcs = vcs;
    }

    @Override
    public void callSelf() {
      ChangesUtil.processItemsByVcs(myIncludedChanges, change -> myVcs, this::process);
    }

    private void process(@NotNull AbstractVcs vcs, @NotNull List<Change> items) {
      if (myVcs.getName().equals(vcs.getName())) {
        CheckinEnvironment environment = vcs.getCheckinEnvironment();
        if (environment != null) {
          Collection<FilePath> paths = ChangesUtil.getPaths(items);
          myPathsToRefresh.addAll(paths);

          List<VcsException> exceptions = environment.commit(items, myCommitMessage, myAdditionalData, myFeedback);
          if (exceptions != null && exceptions.size() > 0) {
            myVcsExceptions.addAll(exceptions);
            myChangesFailedToCommit.addAll(items);
          }
        }
      }
    }

    @Override
    public void afterSuccessfulCheckIn() {
    }

    @Override
    public void afterFailedCheckIn() {
    }

    @Override
    public void doBeforeRefresh() {
    }

    @Override
    public void customRefresh() {
    }

    @Override
    public void doPostRefresh() {
    }
  }

  private abstract static class GeneralCommitProcessor {
    protected final List<FilePath> myPathsToRefresh = newArrayList();
    protected final List<VcsException> myVcsExceptions = newArrayList();
    protected final List<Change> myChangesFailedToCommit = newArrayList();

    public abstract void callSelf();
    public abstract void afterSuccessfulCheckIn();
    public abstract void afterFailedCheckIn();

    public abstract void doBeforeRefresh();
    public abstract void customRefresh();
    public abstract void doPostRefresh();

    public List<FilePath> getPathsToRefresh() {
      return myPathsToRefresh;
    }

    public List<VcsException> getVcsExceptions() {
      return myVcsExceptions;
    }

    public List<Change> getChangesFailedToCommit() {
      return myChangesFailedToCommit;
    }
  }

  private enum ChangeListsModificationAfterCommit {
    DELETE_LIST,
    MOVE_OTHERS,
    NOTHING
  }

  private class CommitProcessor extends GeneralCommitProcessor {
    private boolean myKeepChangeListAfterCommit;
    private LocalHistoryAction myAction;
    private ChangeListsModificationAfterCommit myAfterVcsRefreshModification;
    private boolean myCommitSuccess;
    @Nullable private final AbstractVcs myVcs;

    private CommitProcessor(@Nullable AbstractVcs vcs) {
      myVcs = vcs;
      myAfterVcsRefreshModification = ChangeListsModificationAfterCommit.NOTHING;
      if (myChangeList instanceof LocalChangeList) {
        LocalChangeList localList = (LocalChangeList)myChangeList;
        boolean containsAll = newHashSet(myIncludedChanges).containsAll(newHashSet(myChangeList.getChanges()));
        if (containsAll && !localList.isDefault() && !localList.isReadOnly()) {
          myAfterVcsRefreshModification = ChangeListsModificationAfterCommit.DELETE_LIST;
        }
        else if (myConfiguration.OFFER_MOVE_TO_ANOTHER_CHANGELIST_ON_PARTIAL_COMMIT &&
                 !containsAll &&
                 localList.isDefault() &&
                 myAllOfDefaultChangeListChangesIncluded) {
          myAfterVcsRefreshModification = ChangeListsModificationAfterCommit.MOVE_OTHERS;
        }
      }
    }

    @Override
    public void callSelf() {
      if (myVcs != null && myIncludedChanges.isEmpty()) {
        process(myVcs, myIncludedChanges);
      }
      processChangesByVcs(myProject, myIncludedChanges, this::process);
    }

    private void process(@NotNull AbstractVcs vcs, @NotNull List<Change> items) {
      CheckinEnvironment environment = vcs.getCheckinEnvironment();
      if (environment != null) {
        Collection<FilePath> paths = ChangesUtil.getPaths(items);
        myPathsToRefresh.addAll(paths);
        if (environment.keepChangeListAfterCommit(myChangeList)) {
          myKeepChangeListAfterCommit = true;
        }
        List<VcsException> exceptions = environment.commit(items, myCommitMessage, myAdditionalData, myFeedback);
        if (exceptions != null && exceptions.size() > 0) {
          myVcsExceptions.addAll(exceptions);
          myChangesFailedToCommit.addAll(items);
        }
      }
    }

    @Override
    public void afterSuccessfulCheckIn() {
      myCommitSuccess = true;
    }

    @Override
    public void afterFailedCheckIn() {
      getApplication().invokeLater(
        () -> moveToFailedList(myChangeList, myCommitMessage, getChangesFailedToCommit(),
                               message("commit.dialog.failed.commit.template", myChangeList.getName()), myProject),
        ModalityState.defaultModalityState(), myProject.getDisposed());
    }

    @Override
    public void doBeforeRefresh() {
      ChangeListManagerImpl clManager = (ChangeListManagerImpl)ChangeListManager.getInstance(myProject);
      clManager.showLocalChangesInvalidated();

      myAction = ReadAction.compute(() -> LocalHistory.getInstance().startAction(myActionName));
    }

    @Override
    public void customRefresh() {
      List<Change> toRefresh = newArrayList();
      processChangesByVcs(myProject, myIncludedChanges, (vcs, items) -> {
        CheckinEnvironment ce = vcs.getCheckinEnvironment();
        if (ce != null && ce.isRefreshAfterCommitNeeded()) {
          toRefresh.addAll(items);
        }
      });

      if (toRefresh.isEmpty()) {
        return;
      }

      ProgressIndicator indicator = ProgressManager.getInstance().getProgressIndicator();
      if (indicator != null) {
        indicator.setText(message("commit.dialog.refresh.files"));
      }
      RefreshVFsSynchronously.updateChanges(toRefresh);
    }

    @Override
    public void doPostRefresh() {
      // to be completely sure
      if (myAction != null) {
        myAction.finish();
      }
      if (!myProject.isDisposed()) {
        // after vcs refresh is completed, outdated notifiers should be removed if some exists...
        ChangeListManager clManager = ChangeListManager.getInstance(myProject);
        clManager.invokeAfterUpdate(() -> {
          if (myCommitSuccess) {
            // do delete/ move of change list if needed
            if (ChangeListsModificationAfterCommit.DELETE_LIST.equals(myAfterVcsRefreshModification)) {
              if (!myKeepChangeListAfterCommit) {
                clManager.removeChangeList(myChangeList.getName());
              }
            }
            else if (ChangeListsModificationAfterCommit.MOVE_OTHERS.equals(myAfterVcsRefreshModification)) {
              ChangelistMoveOfferDialog dialog = new ChangelistMoveOfferDialog(myConfiguration);
              if (dialog.showAndGet()) {
                Collection<Change> changes = clManager.getDefaultChangeList().getChanges();
                MoveChangesToAnotherListAction.askAndMove(myProject, changes, emptyList());
              }
            }
          }
          CommittedChangesCache cache = CommittedChangesCache.getInstance(myProject);
          // in background since commit must have authorized
          cache.refreshAllCachesAsync(false, true);
          cache.refreshIncomingChangesAsync();
        }, InvokeAfterUpdateMode.SILENT, null, vcsDirtyScopeManager -> {
          for (FilePath path : getPathsToRefresh()) {
            vcsDirtyScopeManager.fileDirty(path);
          }
        }, null);

        LocalHistory.getInstance().putSystemLabel(myProject, myActionName + ": " + myCommitMessage);
      }
    }
  }

  private void markCommittingDocuments() {
    myCommittingDocuments.addAll(markCommittingDocuments(myProject, myIncludedChanges));
  }

  private void unmarkCommittingDocuments() {
    unmarkCommittingDocuments(myCommittingDocuments);
    myCommittingDocuments.clear();
  }

  /**
   * Marks {@link Document documents} related to the given changes as "being committed".
   * @return documents which were marked that way.
   * @see #unmarkCommittingDocuments(Collection)
   * @see VetoSavingCommittingDocumentsAdapter
   */
  @NotNull
  private static Collection<Document> markCommittingDocuments(@NotNull Project project, @NotNull List<Change> changes) {
    Collection<Document> committingDocs = newArrayList();
    for (Change change : changes) {
      VirtualFile virtualFile = ChangesUtil.getFilePath(change).getVirtualFile();
      if (virtualFile != null && !virtualFile.getFileType().isBinary()) {
        Document doc = FileDocumentManager.getInstance().getDocument(virtualFile);
        if (doc != null) {
          doc.putUserData(DOCUMENT_BEING_COMMITTED_KEY, project);
          committingDocs.add(doc);
        }
      }
    }
    return committingDocs;
  }

  /**
   * Removes the "being committed marker" from the given {@link Document documents}.
   * @see #markCommittingDocuments(Project, List)
   * @see VetoSavingCommittingDocumentsAdapter
   */
  private static void unmarkCommittingDocuments(@NotNull Collection<Document> committingDocs) {
    for (Document doc : committingDocs) {
      doc.putUserData(DOCUMENT_BEING_COMMITTED_KEY, null);
    }
  }

  private void commitCompleted(List<VcsException> allExceptions, GeneralCommitProcessor processor) {
    List<VcsException> errors = collectErrors(allExceptions);
    boolean noErrors = errors.isEmpty();
    boolean noWarnings = allExceptions.isEmpty();

    if (noErrors) {
      for (CheckinHandler handler : myHandlers) {
        handler.checkinSuccessful();
      }

      processor.afterSuccessfulCheckIn();

      if (myCustomResultHandler != null) {
        myCustomResultHandler.onSuccess(myCommitMessage);
      }
      else {
        reportResult(processor);
      }

      if (noWarnings) {
        ProgressIndicator indicator = ProgressManager.getInstance().getProgressIndicator();
        if (indicator != null) {
          indicator.setText(message("commit.dialog.completed.successfully"));
        }
      }
    }
    else {
      for (CheckinHandler handler : myHandlers) {
        handler.checkinFailed(errors);
      }

      processor.afterFailedCheckIn();

      if (myCustomResultHandler != null) {
        myCustomResultHandler.onFailure();
      }
      else {
        reportResult(processor);
      }
    }
  }

  @CalledInAwt
  public static void moveToFailedList(ChangeList changeList,
                                      String commitMessage,
                                      List<Change> failedChanges,
                                      String newChangelistName,
                                      Project project) {
    // No need to move since we'll get exactly the same changelist.
    if (failedChanges.containsAll(changeList.getChanges())) return;

    VcsConfiguration configuration = VcsConfiguration.getInstance(project);
    if (configuration.MOVE_TO_FAILED_COMMIT_CHANGELIST != VcsShowConfirmationOption.Value.DO_ACTION_SILENTLY) {
      VcsShowConfirmationOption option = new VcsShowConfirmationOption() {
        @Override
        public Value getValue() {
          return configuration.MOVE_TO_FAILED_COMMIT_CHANGELIST;
        }

        @Override
        public void setValue(Value value) {
          configuration.MOVE_TO_FAILED_COMMIT_CHANGELIST = value;
        }

        @Override
        public boolean isPersistent() {
          return true;
        }
      };
      boolean result =
        requestForConfirmation(option, project, message("commit.failed.confirm.prompt"), message("commit.failed.confirm.title"),
                               getQuestionIcon());
      if (!result) return;
    }

    ChangeListManager changeListManager = ChangeListManager.getInstance(project);
    int index = 1;
    String failedListName = newChangelistName;
    while (changeListManager.findChangeList(failedListName) != null) {
      index++;
      failedListName = newChangelistName + " (" + index + ")";
    }

    LocalChangeList failedList = changeListManager.addChangeList(failedListName, commitMessage);
    changeListManager.moveChangesTo(failedList, failedChanges.toArray(new Change[failedChanges.size()]));
  }

  private static List<VcsException> collectErrors(List<VcsException> vcsExceptions) {
    List<VcsException> result = newArrayList();
    for (VcsException vcsException : vcsExceptions) {
      if (!vcsException.isWarning()) {
        result.add(vcsException);
      }
    }
    return result;
  }
}
