// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.psi.impl.file.impl;

import com.intellij.AppTopics;
import com.intellij.ProjectTopics;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileDocumentManagerListener;
import com.intellij.openapi.fileEditor.impl.FileDocumentManagerImpl;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeEvent;
import com.intellij.openapi.fileTypes.FileTypeListener;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.roots.*;
import com.intellij.openapi.roots.impl.PushedFilePropertiesUpdater;
import com.intellij.openapi.roots.impl.PushedFilePropertiesUpdaterImpl;
import com.intellij.openapi.startup.StartupManager;
import com.intellij.openapi.util.io.FileUtilRt;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.vfs.*;
import com.intellij.openapi.vfs.impl.BulkVirtualFileListenerAdapter;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.project.ProjectKt;
import com.intellij.psi.*;
import com.intellij.psi.impl.DebugUtil;
import com.intellij.psi.impl.PsiManagerImpl;
import com.intellij.psi.impl.PsiTreeChangeEventImpl;
import com.intellij.util.FileContentUtilCore;
import com.intellij.util.messages.MessageBusConnection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class PsiVFSListener implements VirtualFileListener, BulkFileListener {
  private static final Logger LOG = Logger.getInstance("#com.intellij.psi.impl.file.impl.PsiVFSListener");

  private final FileTypeManager myFileTypeManager;
  private final ProjectRootManager myProjectRootManager;
  private final PsiManagerImpl myManager;
  private final FileManagerImpl myFileManager;
  private final Project myProject;
  private boolean myReportedUnloadedPsiChange;

  private static final AtomicBoolean ourGlobalListenerInstalled = new AtomicBoolean(false);

  public PsiVFSListener(Project project) {
    installGlobalListener();

    myProject = project;
    myFileTypeManager = FileTypeManager.getInstance();
    myProjectRootManager = ProjectRootManager.getInstance(project);
    myManager = (PsiManagerImpl) PsiManager.getInstance(project);
    myFileManager = (FileManagerImpl) myManager.getFileManager();

    StartupManager.getInstance(project).registerPreStartupActivity(() -> {
      MessageBusConnection connection = project.getMessageBus().connect(project);
      connection.subscribe(ProjectTopics.PROJECT_ROOTS, new MyModuleRootListener());
      connection.subscribe(FileTypeManager.TOPIC, new FileTypeListener() {
        @Override
        public void fileTypesChanged(@NotNull FileTypeEvent e) {
          myFileManager.processFileTypesChanged();
        }
      });
      connection.subscribe(AppTopics.FILE_DOCUMENT_SYNC, new MyFileDocumentManagerAdapter());
    });
  }

  /**
   * This code is implemented as static method (and not static constructor, as it was done before) to prevent installing listeners in Upsource
   */
  private static void installGlobalListener() {
    if (ourGlobalListenerInstalled.compareAndSet(false, true)) {
      ApplicationManager.getApplication().getMessageBus().connect().subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
        @Override
        public void before(@NotNull List<? extends VFileEvent> events) {
          for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            PsiVFSListener listener = project.getComponent(PsiVFSListener.class);
            listener.before(events);
          }
        }

        @Override
        public void after(@NotNull List<? extends VFileEvent> events) {
          Project[] projects = ProjectManager.getInstance().getOpenProjects();

          // let PushedFilePropertiesUpdater process all pending vfs events and update file properties before we issue PSI events
          for (Project project : projects) {
            PushedFilePropertiesUpdater updater = PushedFilePropertiesUpdater.getInstance(project);
            if (updater instanceof PushedFilePropertiesUpdaterImpl) { // false in upsource
              ((PushedFilePropertiesUpdaterImpl)updater).processAfterVfsChanges(events);
            }
          }
          for (Project project : projects) {
            PsiVFSListener listener = project.getComponent(PsiVFSListener.class);
            listener.myReportedUnloadedPsiChange = false;
            listener.after(events);
            listener.myReportedUnloadedPsiChange = false;
          }
        }
      });
    }
  }

  @Nullable
  private PsiDirectory getCachedDirectory(VirtualFile parent) {
    return parent == null ? null : myFileManager.getCachedDirectory(parent);
  }

  @Override
  public void fileCopied(@NotNull VirtualFileCopyEvent event) {
    fileCreated(event);
  }

  @Override
  public void fileCreated(@NotNull VirtualFileEvent event) {
    final VirtualFile vFile = event.getFile();

    ApplicationManager.getApplication().runWriteAction(
      (ExternalChangeAction)() -> {
        VirtualFile parent = vFile.getParent();
        PsiDirectory parentDir = getCachedDirectory(parent);
        if (parentDir == null) {
          // parent directory was never accessed via PSI
          handleVfsChangeWithoutPsi(vFile);
          return;
        }
        PsiFileSystemItem item = vFile.isDirectory() ? myFileManager.findDirectory(vFile) : myFileManager.findFile(vFile);
        if (item != null && item.getProject() == myManager.getProject()) {
          PsiTreeChangeEventImpl treeEvent = new PsiTreeChangeEventImpl(myManager);
          treeEvent.setParent(parentDir);
          myManager.beforeChildAddition(treeEvent);
          treeEvent.setChild(item);
          myManager.childAdded(treeEvent);
        }
      }
    );
  }

  @Override
  public void beforeFileDeletion(@NotNull VirtualFileEvent event) {
    final VirtualFile vFile = event.getFile();

    VirtualFile parent = vFile.getParent();
    final PsiDirectory parentDir = getCachedDirectory(parent);
    if (parentDir == null) return; // do not notify listeners if parent directory was never accessed via PSI

    ApplicationManager.getApplication().runWriteAction(
      (ExternalChangeAction)() -> {
        PsiFileSystemItem item = vFile.isDirectory() ? myFileManager.findDirectory(vFile) : myFileManager.getCachedPsiFile(vFile);
        if (item != null) {
          PsiTreeChangeEventImpl treeEvent = new PsiTreeChangeEventImpl(myManager);
          treeEvent.setParent(parentDir);
          treeEvent.setChild(item);
          myManager.beforeChildRemoval(treeEvent);
        }
      }
    );
  }

  @Override
  public void fileDeleted(@NotNull final VirtualFileEvent event) {
    final VirtualFile vFile = event.getFile();

    VirtualFile parent = event.getParent();
    final PsiDirectory parentDir = getCachedDirectory(parent);

    final PsiFile psiFile = myFileManager.getCachedPsiFileInner(vFile);
    PsiElement element;
    if (psiFile != null) {
      clearViewProvider(vFile, "PSI fileDeleted");
      element = psiFile;
    }
    else {
      final PsiDirectory psiDir = myFileManager.getCachedDirectory(vFile);
      if (psiDir != null) {
        myFileManager.removeInvalidFilesAndDirs(false);
        element = psiDir;
      }
      else if (parent != null) {
        handleVfsChangeWithoutPsi(parent);
        return;
      }
      else {
        element = null;
      }
    }
    if (element != null && parentDir != null) {
      ApplicationManager.getApplication().runWriteAction((ExternalChangeAction)() -> {
        PsiTreeChangeEventImpl treeEvent = new PsiTreeChangeEventImpl(myManager);
        treeEvent.setParent(parentDir);
        treeEvent.setChild(element);
        myManager.childRemoved(treeEvent);
      });
    }
  }

  private void clearViewProvider(@NotNull VirtualFile vFile, @NotNull String why) {
    DebugUtil.performPsiModification(why, ()->myFileManager.setViewProvider(vFile, null));
  }

  @Override
  public void beforePropertyChange(@NotNull final VirtualFilePropertyEvent event) {
    final VirtualFile vFile = event.getFile();
    final String propertyName = event.getPropertyName();

    final FileViewProvider viewProvider = myFileManager.findCachedViewProvider(vFile);

    VirtualFile parent = vFile.getParent();
    final PsiDirectory parentDir = viewProvider != null && parent != null ? myFileManager.findDirectory(parent) : getCachedDirectory(parent);
    if (parent != null && parentDir == null) return; // do not notifyListeners event if parent directory was never accessed via PSI

    ApplicationManager.getApplication().runWriteAction(
      (ExternalChangeAction)() -> {
        PsiTreeChangeEventImpl treeEvent = new PsiTreeChangeEventImpl(myManager);
        treeEvent.setParent(parentDir);

        if (VirtualFile.PROP_NAME.equals(propertyName)) {
          final String newName = (String)event.getNewValue();

          if (parentDir == null) return;

          if (vFile.isDirectory()) {
            PsiDirectory psiDir = myFileManager.findDirectory(vFile);
            if (psiDir != null) {
              if (!myFileTypeManager.isFileIgnored(newName)) {
                treeEvent.setChild(psiDir);
                treeEvent.setPropertyName(PsiTreeChangeEvent.PROP_DIRECTORY_NAME);
                treeEvent.setOldValue(vFile.getName());
                treeEvent.setNewValue(newName);
                myManager.beforePropertyChange(treeEvent);
              }
              else {
                treeEvent.setChild(psiDir);
                myManager.beforeChildRemoval(treeEvent);
              }
            }
            else {
              if ((!Registry.is("ide.hide.excluded.files") || !isExcludeRoot(vFile)) && !myFileTypeManager.isFileIgnored(newName)) {
                myManager.beforeChildAddition(treeEvent);
              }
            }
          }
          else {
            final FileViewProvider viewProvider1 = myFileManager.findViewProvider(vFile);
            PsiFile psiFile = viewProvider1.getPsi(viewProvider1.getBaseLanguage());
            PsiFile psiFile1 = createFileCopyWithNewName(vFile, newName);

            if (psiFile != null) {
              if (psiFile1 == null) {
                treeEvent.setChild(psiFile);
                myManager.beforeChildRemoval(treeEvent);
              }
              else if (!psiFile1.getClass().equals(psiFile.getClass())) {
                treeEvent.setOldChild(psiFile);
                myManager.beforeChildReplacement(treeEvent);
              }
              else {
                treeEvent.setChild(psiFile);
                treeEvent.setPropertyName(PsiTreeChangeEvent.PROP_FILE_NAME);
                treeEvent.setOldValue(vFile.getName());
                treeEvent.setNewValue(newName);
                myManager.beforePropertyChange(treeEvent);
              }
            }
            else {
              if (psiFile1 != null) {
                myManager.beforeChildAddition(treeEvent);
              }
            }
          }
        }
        else if (VirtualFile.PROP_WRITABLE.equals(propertyName)) {
          PsiFile psiFile = myFileManager.getCachedPsiFileInner(vFile);
          if (psiFile == null) return;

          treeEvent.setElement(psiFile);
          treeEvent.setPropertyName(PsiTreeChangeEvent.PROP_WRITABLE);
          treeEvent.setOldValue(event.getOldValue());
          treeEvent.setNewValue(event.getNewValue());
          myManager.beforePropertyChange(treeEvent);
        }
      }
    );
  }

  private boolean isExcludeRoot(VirtualFile file) {
    VirtualFile parent = file.getParent();
    if (parent == null) return false;

    Module module = myProjectRootManager.getFileIndex().getModuleForFile(parent);
    if (module == null) return false;
    VirtualFile[] excludeRoots = ModuleRootManager.getInstance(module).getExcludeRoots();
    for (VirtualFile root : excludeRoots) {
      if (root.equals(file)) return true;
    }
    return false;
  }

  @Override
  public void propertyChanged(@NotNull final VirtualFilePropertyEvent event) {
    final String propertyName = event.getPropertyName();
    final VirtualFile vFile = event.getFile();

    FileViewProvider oldFileViewProvider = myFileManager.findCachedViewProvider(vFile);
    PsiFile oldPsiFile = myFileManager.getCachedPsiFile(vFile);

    VirtualFile parent = vFile.getParent();
    final PsiDirectory parentDir = oldPsiFile != null && parent != null ? myFileManager.findDirectory(parent) : getCachedDirectory(parent);

    if (oldFileViewProvider != null // there is no need to rebuild if there were no PSI in the first place
        && FileContentUtilCore.FORCE_RELOAD_REQUESTOR.equals(event.getRequestor())) {
      myFileManager.forceReload(vFile);
      return;
    }

    // do not suppress reparse request for light files
    if (parentDir == null) {
      boolean fire = VirtualFile.PROP_NAME.equals(propertyName) && vFile.isDirectory();
      if (fire) {
        PsiDirectory psiDir = myFileManager.getCachedDirectory(vFile);
        fire = psiDir != null;
      }
      if (!fire && !VirtualFile.PROP_WRITABLE.equals(propertyName)) {
        handleVfsChangeWithoutPsi(vFile);
        return;
      }
    }

    ApplicationManager.getApplication().runWriteAction(
      (ExternalChangeAction)() -> {
        PsiTreeChangeEventImpl treeEvent = new PsiTreeChangeEventImpl(myManager);
        treeEvent.setParent(parentDir);

        if (VirtualFile.PROP_NAME.equals(propertyName)) {
          if (vFile.isDirectory()) {
            PsiDirectory psiDir = myFileManager.getCachedDirectory(vFile);
            if (psiDir != null) {
              if (myFileTypeManager.isFileIgnored(vFile)) {
                myFileManager.removeFilesAndDirsRecursively(vFile);

                treeEvent.setChild(psiDir);
                myManager.childRemoved(treeEvent);
              }
              else {
                treeEvent.setElement(psiDir);
                treeEvent.setPropertyName(PsiTreeChangeEvent.PROP_DIRECTORY_NAME);
                treeEvent.setOldValue(event.getOldValue());
                treeEvent.setNewValue(event.getNewValue());
                myManager.propertyChanged(treeEvent);
              }
            }
            else {
              PsiDirectory psiDir1 = myFileManager.findDirectory(vFile);
              if (psiDir1 != null) {
                treeEvent.setChild(psiDir1);
                myManager.childAdded(treeEvent);
              }
            }
          }
          else {
            final FileViewProvider fileViewProvider = myFileManager.createFileViewProvider(vFile, true);
            final PsiFile newPsiFile = fileViewProvider.getPsi(fileViewProvider.getBaseLanguage());
            if(oldPsiFile != null) {
              if (newPsiFile == null) {
                clearViewProvider(vFile, "PSI renamed");

                treeEvent.setChild(oldPsiFile);
                myManager.childRemoved(treeEvent);
              }
              else if (!FileManagerImpl.areViewProvidersEquivalent(fileViewProvider, oldFileViewProvider)) {
                myFileManager.setViewProvider(vFile, fileViewProvider);

                treeEvent.setOldChild(oldPsiFile);
                treeEvent.setNewChild(newPsiFile);
                myManager.childReplaced(treeEvent);
              }
              else {
                FileManagerImpl.clearPsiCaches(oldFileViewProvider);

                treeEvent.setElement(oldPsiFile);
                treeEvent.setPropertyName(PsiTreeChangeEvent.PROP_FILE_NAME);
                treeEvent.setOldValue(event.getOldValue());
                treeEvent.setNewValue(event.getNewValue());
                myManager.propertyChanged(treeEvent);
              }
            }
            else if (newPsiFile != null) {
              myFileManager.setViewProvider(vFile, fileViewProvider);
              if (parentDir != null) {
                treeEvent.setChild(newPsiFile);
                myManager.childAdded(treeEvent);
              }
            }
          }
        }
        else if (VirtualFile.PROP_WRITABLE.equals(propertyName)) {
          if (oldPsiFile == null) return;

          treeEvent.setElement(oldPsiFile);
          treeEvent.setPropertyName(PsiTreeChangeEvent.PROP_WRITABLE);
          treeEvent.setOldValue(event.getOldValue());
          treeEvent.setNewValue(event.getNewValue());
          myManager.propertyChanged(treeEvent);
        }
        else if (VirtualFile.PROP_ENCODING.equals(propertyName)) {
          if (oldPsiFile == null) return;

          treeEvent.setElement(oldPsiFile);
          treeEvent.setPropertyName(VirtualFile.PROP_ENCODING);
          treeEvent.setOldValue(event.getOldValue());
          treeEvent.setNewValue(event.getNewValue());
          myManager.propertyChanged(treeEvent);
        }
      }
    );
  }

  @Override
  public void beforeFileMovement(@NotNull VirtualFileMoveEvent event) {
    final VirtualFile vFile = event.getFile();

    final PsiDirectory oldParentDir = myFileManager.findDirectory(event.getOldParent());
    final PsiDirectory newParentDir = myFileManager.findDirectory(event.getNewParent());
    if (oldParentDir == null && newParentDir == null) return;
    if (myFileTypeManager.isFileIgnored(vFile)) return;

    ApplicationManager.getApplication().runWriteAction(
      (ExternalChangeAction)() -> {
        PsiTreeChangeEventImpl treeEvent = new PsiTreeChangeEventImpl(myManager);

        boolean isExcluded = vFile.isDirectory() &&
                             Registry.is("ide.hide.excluded.files") && myProjectRootManager.getFileIndex().isExcluded(vFile);
        if (oldParentDir != null && !isExcluded) {
          if (newParentDir != null) {
            treeEvent.setOldParent(oldParentDir);
            treeEvent.setNewParent(newParentDir);
            if (vFile.isDirectory()) {
              PsiDirectory psiDir = myFileManager.findDirectory(vFile);
              treeEvent.setChild(psiDir);
            }
            else {
              PsiFile psiFile = myFileManager.findFile(vFile);
              treeEvent.setChild(psiFile);
            }
            myManager.beforeChildMovement(treeEvent);
          }
          else {
            treeEvent.setParent(oldParentDir);
            if (vFile.isDirectory()) {
              PsiDirectory psiDir = myFileManager.findDirectory(vFile);
              treeEvent.setChild(psiDir);
            }
            else {
              PsiFile psiFile = myFileManager.findFile(vFile);
              treeEvent.setChild(psiFile);
            }
            myManager.beforeChildRemoval(treeEvent);
          }
        }
        else {
          LOG.assertTrue(newParentDir != null); // checked above
          treeEvent.setParent(newParentDir);
          myManager.beforeChildAddition(treeEvent);
        }
      }
    );
  }

  @Override
  public void fileMoved(@NotNull VirtualFileMoveEvent event) {
    final VirtualFile vFile = event.getFile();

    final PsiDirectory oldParentDir = myFileManager.findDirectory(event.getOldParent());
    final PsiDirectory newParentDir = myFileManager.findDirectory(event.getNewParent());
    if (oldParentDir == null && newParentDir == null) return;

    final PsiElement oldElement = vFile.isDirectory()
                                  ? myFileManager.getCachedDirectory(vFile)
                                  : myFileManager.getCachedPsiFileInner(vFile);
    myFileManager.removeInvalidFilesAndDirs(true);
    final PsiElement newElement;
    final FileViewProvider newViewProvider;
    if (!vFile.isDirectory()){
      newViewProvider = myFileManager.createFileViewProvider(vFile, true);
      newElement = newViewProvider.getPsi(myFileManager.findViewProvider(vFile).getBaseLanguage());
    }
    else {
      newElement = myFileManager.findDirectory(vFile);
      newViewProvider = null;
    }

    if (oldElement == null && newElement == null) return;

    ApplicationManager.getApplication().runWriteAction(
      (ExternalChangeAction)() -> {
        PsiTreeChangeEventImpl treeEvent = new PsiTreeChangeEventImpl(myManager);
        if (oldElement == null) {
          myFileManager.setViewProvider(vFile, newViewProvider);
          treeEvent.setParent(newParentDir);
          treeEvent.setChild(newElement);
          myManager.childAdded(treeEvent);
        }
        else {
          if (newElement == null) {
            clearViewProvider(vFile, "PSI moved");
            treeEvent.setParent(oldParentDir);
            treeEvent.setChild(oldElement);
            myManager.childRemoved(treeEvent);
          }
          else {
            if (newElement instanceof PsiDirectory || FileManagerImpl.areViewProvidersEquivalent(newViewProvider, ((PsiFile) oldElement).getViewProvider())) {
              treeEvent.setOldParent(oldParentDir);
              treeEvent.setNewParent(newParentDir);
              treeEvent.setChild(oldElement);
              myManager.childMoved(treeEvent);
            }
            else {
              myFileManager.setViewProvider(vFile, newViewProvider);
              PsiTreeChangeEventImpl treeRemoveEvent = new PsiTreeChangeEventImpl(myManager);
              treeRemoveEvent.setParent(oldParentDir);
              treeRemoveEvent.setChild(oldElement);
              myManager.childRemoved(treeRemoveEvent);
              PsiTreeChangeEventImpl treeAddEvent = new PsiTreeChangeEventImpl(myManager);
              treeAddEvent.setParent(newParentDir);
              treeAddEvent.setChild(newElement);
              myManager.childAdded(treeAddEvent);
            }
          }
        }
      }
    );
  }

  @Nullable
  private PsiFile createFileCopyWithNewName(VirtualFile vFile, String name) {
    // TODO[ik] remove this. Event handling and generation must be in view providers mechanism since we
    // need to track changes in _all_ psi views (e.g. namespace changes in XML)
    final FileTypeManager instance = FileTypeManager.getInstance();
    if(instance.isFileIgnored(name)) return null;
    final FileType fileTypeByFileName = instance.getFileTypeByFileName(name);
    final Document document = FileDocumentManager.getInstance().getDocument(vFile);
    return PsiFileFactory.getInstance(myManager.getProject()).createFileFromText(name, fileTypeByFileName,
                                                                                 document != null ? document.getCharsSequence() : "", vFile.getModificationStamp(),
                                                                                 true, false);
  }

  private class MyModuleRootListener implements ModuleRootListener {
    private VirtualFile[] myOldContentRoots;
    private int depthCounter; // accessed from within write action only
    @Override
    public void beforeRootsChange(final ModuleRootEvent event) {
      if (event.isCausedByFileTypesChange()) return;
      ApplicationManager.getApplication().runWriteAction(
        (ExternalChangeAction)() -> {
          depthCounter++;
          if (depthCounter > 1) return;

          PsiTreeChangeEventImpl treeEvent = new PsiTreeChangeEventImpl(myManager);
          treeEvent.setPropertyName(PsiTreeChangeEvent.PROP_ROOTS);
          final VirtualFile[] contentRoots = myProjectRootManager.getContentRoots();
          LOG.assertTrue(myOldContentRoots == null);
          myOldContentRoots = contentRoots;
          treeEvent.setOldValue(contentRoots);
          myManager.beforePropertyChange(treeEvent);
        }
      );
    }

    @Override
    public void rootsChanged(final ModuleRootEvent event) {
      myFileManager.dispatchPendingEvents();

      if (event.isCausedByFileTypesChange()) return;
      ApplicationManager.getApplication().runWriteAction(
        (ExternalChangeAction)() -> {
          depthCounter--;
          assert depthCounter >= 0 : depthCounter;
          if (depthCounter > 0) return;

          DebugUtil.performPsiModification(null, () -> myFileManager.possiblyInvalidatePhysicalPsi());

          PsiTreeChangeEventImpl treeEvent = new PsiTreeChangeEventImpl(myManager);
          treeEvent.setPropertyName(PsiTreeChangeEvent.PROP_ROOTS);
          final VirtualFile[] contentRoots = myProjectRootManager.getContentRoots();
          treeEvent.setNewValue(contentRoots);
          LOG.assertTrue(myOldContentRoots != null);
          treeEvent.setOldValue(myOldContentRoots);
          myOldContentRoots = null;
          myManager.propertyChanged(treeEvent);
        }
      );
    }
  }

  private class MyFileDocumentManagerAdapter implements FileDocumentManagerListener {
    @Override
    public void fileWithNoDocumentChanged(@NotNull final VirtualFile file) {
      FileViewProvider viewProvider = myFileManager.findCachedViewProvider(file);
      if (viewProvider != null) {
        ApplicationManager.getApplication().runWriteAction(
          (ExternalChangeAction)() -> {
            if (FileDocumentManagerImpl.recomputeFileTypeIfNecessary(file)) {
              myFileManager.forceReload(file);
            }
            else {
              myFileManager.reloadPsiAfterTextChange(viewProvider, file);
            }
          }
        );
      }
      else {
        handleVfsChangeWithoutPsi(file);
      }
    }

    @Override
    public void fileContentReloaded(@NotNull VirtualFile file, @NotNull Document document) {
      FileViewProvider psiFile = myFileManager.findCachedViewProvider(file);
      if (!file.isValid() || psiFile == null || !FileUtilRt.isTooLarge(file.getLength()) || psiFile instanceof PsiLargeFile) return;
      ApplicationManager.getApplication().runWriteAction((ExternalChangeAction)() -> myFileManager.reloadPsiAfterTextChange(psiFile, file));
    }
  }

  private void handleVfsChangeWithoutPsi(@NotNull VirtualFile vFile) {
    if (!myReportedUnloadedPsiChange && isInRootModel(vFile)) {
      myFileManager.firePropertyChangedForUnloadedPsi();
      myReportedUnloadedPsiChange = true;
    }
  }

  private boolean isInRootModel(@NotNull VirtualFile file) {
    if (ProjectKt.getStateStore(myProject).isProjectFile(file)) {
      return false;
    }

    ProjectFileIndex index = ProjectFileIndex.SERVICE.getInstance(myProject);
    return index.isInContent(file) || index.isInLibraryClasses(file) || index.isInLibrarySource(file);
  }

  @Override
  public void before(@NotNull List<? extends VFileEvent> events) {
    for (VFileEvent event : events) {
      BulkVirtualFileListenerAdapter.fireBefore(this, event);
    }
  }

  @Override
  public void after(@NotNull List<? extends VFileEvent> events) {
    for (VFileEvent event : events) {
      BulkVirtualFileListenerAdapter.fireAfter(this, event);
    }
  }
}
