package org.bimserver.database.actions;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.bimserver.changes.Change;
import org.bimserver.changes.CreateObjectChange;
import org.bimserver.changes.RemoveObjectChange;
import org.bimserver.database.BimDatabaseException;
import org.bimserver.database.BimDatabaseSession;
import org.bimserver.database.BimDeadlockException;
import org.bimserver.emf.IdEObject;
import org.bimserver.mail.MailSystem;
import org.bimserver.models.log.AccessMethod;
import org.bimserver.models.log.LogFactory;
import org.bimserver.models.log.NewRevisionAdded;
import org.bimserver.models.store.CheckinState;
import org.bimserver.models.store.ConcreteRevision;
import org.bimserver.models.store.Project;
import org.bimserver.models.store.Revision;
import org.bimserver.models.store.User;
import org.bimserver.rights.RightsManager;
import org.bimserver.shared.exceptions.UserException;

public class CommitTransactionDatabaseAction extends GenericCheckinDatabaseAction {

	private final Set<Change> changes;
	private final long currentUoid;
	private final String comment;
	private final int pid;
	private Revision revision;

	public CommitTransactionDatabaseAction(BimDatabaseSession bimDatabaseSession, AccessMethod accessMethod, Set<Change> changes, long currentUoid, int pid,
			String comment) {
		super(bimDatabaseSession, accessMethod, null);
		this.changes = changes;
		this.currentUoid = currentUoid;
		this.pid = pid;
		this.comment = comment;
	}

	@Override
	public ConcreteRevision execute() throws UserException, BimDeadlockException, BimDatabaseException {
		Project project = getProjectById(pid);
		User user = getUserByUoid(currentUoid);
		if (project == null) {
			throw new UserException("Project with pid " + pid + " not found");
		}
		if (!RightsManager.hasRightsOnProjectOrSuperProjects(user, project)) {
			throw new UserException("User has no rights to checkin models to this project");
		}
		if (!MailSystem.isValidEmailAddress(user.getUsername())) {
			throw new UserException("Users must have a valid e-mail address to checkin");
		}
		if (!project.getRevisions().isEmpty() && project.getRevisions().get(project.getRevisions().size() - 1).getState() == CheckinState.STORING) {
			throw new UserException("Another checkin on this project is currently running, please wait and try again");
		}
		long size = 0;
		if (project.getLastRevision() != null) {
			for (ConcreteRevision concreteRevision : project.getLastRevision().getConcreteRevisions()) {
				size += concreteRevision.getSize();
			}
		}
		for (Change change : changes) {
			if (change instanceof CreateObjectChange) {
				size++;
			} else if (change instanceof RemoveObjectChange) {
				size--;
			}
		}
		ConcreteRevision concreteRevision = createNewConcreteRevision(getDatabaseSession(), size, project.getOid(), currentUoid, comment.trim(), CheckinState.DONE);
		revision = concreteRevision.getRevisions().get(0);
		project.setLastRevision(revision);
		NewRevisionAdded newRevisionAdded = LogFactory.eINSTANCE.createNewRevisionAdded();
		newRevisionAdded.setDate(new Date());
		newRevisionAdded.setExecutor(user);
		newRevisionAdded.setRevision(concreteRevision.getRevisions().get(0));
		newRevisionAdded.setAccessMethod(getAccessMethod());
		
		// First create all new objects
		Map<Long, IdEObject> created = new HashMap<Long, IdEObject>();
		for (Change change : changes) {
			if (change instanceof CreateObjectChange) {
				change.execute(pid, concreteRevision.getId(), getDatabaseSession(), created);
			}
		}
		// Then do the rest
		for (Change change : changes) {
			if (!(change instanceof CreateObjectChange)) {
				change.execute(pid, concreteRevision.getId(), getDatabaseSession(), created);
			}
		}
		getDatabaseSession().store(newRevisionAdded);
		getDatabaseSession().store(concreteRevision);
		getDatabaseSession().store(project);
		return concreteRevision;
	}

	public Revision getRevision() {
		return revision;
	}
}
