package com.brainfryd.bitbucket.gitnotes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import com.atlassian.bitbucket.commit.Changeset;
import com.atlassian.bitbucket.commit.ChangesetsRequest;
import com.atlassian.bitbucket.commit.Commit;
import com.atlassian.bitbucket.commit.CommitService;
import com.atlassian.bitbucket.commit.CommitsRequest;
import com.atlassian.bitbucket.content.Change;
import com.atlassian.bitbucket.content.ContentService;
import com.atlassian.bitbucket.io.TypeAwareOutputSupplier;
import com.atlassian.bitbucket.repository.Ref;
import com.atlassian.bitbucket.repository.RefService;
import com.atlassian.bitbucket.repository.Repository;
import com.atlassian.bitbucket.repository.ResolveRefRequest;
import com.atlassian.bitbucket.util.Page;
import com.atlassian.bitbucket.util.PageRequestImpl;
import com.atlassian.plugin.web.model.WebPanel;

public class GitNotesWebPanel implements WebPanel {
	private static final int MAX_NOTES_TO_SEARCH = 1000;
	private static final String DEFAULT_NOTE = "";

	private RefService refService;
	private CommitService commitService;
	private ContentService contentService;

	public GitNotesWebPanel(RefService refService, CommitService commitService, ContentService contentService) {
		this.refService = refService;
		this.commitService = commitService;
		this.contentService = contentService;
	}

	@Override
	public String getHtml(Map<String, Object> context) {
		return determineGitNote(context);
	}

	@Override
	public void writeHtml(Writer writer, Map<String, Object> context) throws IOException {
		writer.write(determineGitNote(context));
	}

	private String determineGitNote(Map<String, Object> context) {
		Repository repository = (Repository) context.get("repository");
		Commit commit = (Commit) context.get("commit");

		List<String> noteCommitIds = noteCommitIds(repository);
		String noteCommitId = findCorrespondingNoteCommitId(commit, repository, noteCommitIds);
		if (noteCommitId != null) {
			return getNote(repository, noteCommitId, commit.getId());
		}
		return DEFAULT_NOTE;
	}

	private List<String> noteCommitIds(Repository repository) {
		Ref noteRef = refService.resolveRef(new ResolveRefRequest.Builder(repository).refId("notes/commits").build());
		if (noteRef != null) {
			String latestNoteCommit = noteRef.getLatestCommit();
			Page<Commit> noteCommits = commitService.getCommits(
					new CommitsRequest.Builder(repository, latestNoteCommit).build(),
					new PageRequestImpl(0, MAX_NOTES_TO_SEARCH));
			List<String> noteCommitIds = StreamSupport.stream(noteCommits.getValues().spliterator(), false)
					.map(Commit::getId).collect(Collectors.toList());
			return noteCommitIds;
		}
		return Collections.emptyList();
	}

	private String findCorrespondingNoteCommitId(Commit commit, Repository repository, List<String> noteCommitIds) {
		Page<Changeset> changesets = commitService.getChangesets(
				new ChangesetsRequest.Builder(repository).commitIds(noteCommitIds).build(),
				new PageRequestImpl(0, noteCommitIds.size()));
		Iterator<Changeset> changesetsIterator = changesets.getValues().iterator();
		while (changesetsIterator.hasNext()) {
			Changeset changeset = changesetsIterator.next();
			Page<Change> page = changeset.getChanges();
			if (page != null) {
				Iterator<Change> changes = page.getValues().iterator();
				if (changes.hasNext()) {
					if (isCorrespondingNoteCommit(commit, changes.next())) {
						return changeset.getToCommit().getId();
					}
				}
			}
		}
		return null;
	}

	private static boolean isCorrespondingNoteCommit(Commit commit, Change change) {
		return commit.getId().equals(change.getPath().getName());
	}

	private String getNote(Repository repository, String noteCommitId, String commitId) {
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		contentService.streamFile(repository, noteCommitId, commitId, new TypeAwareOutputSupplier() {
			@Override
			public OutputStream getStream(String contentType) throws IOException {
				return out;
			}
		});
		return new String(out.toByteArray());
	}
}
