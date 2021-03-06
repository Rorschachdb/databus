package controllers.gui;

import gov.nrel.util.Utility;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alvazan.orm.api.base.CursorToMany;
import com.alvazan.orm.api.base.spi.UniqueKeyGenerator;
import com.alvazan.play.NoSql;

import controllers.gui.auth.GuiSecure;
import controllers.gui.util.AccessInfo;

import play.mvc.Controller;
import play.mvc.With;
import play.mvc.results.NotFound;

import models.Entity;
import models.EntityGroup;
import models.EntityGroupXref;
import models.EntityUser;
import models.KeyToTableName;
import models.PermissionType;
import models.RoleMapping;
import models.SecureResource;
import models.SecureResourceGroupXref;
import models.SecureSchema;
import models.SecureTable;
import models.SecurityGroup;
import models.UserType;

@With(GuiSecure.class)
public class MyGroups extends Controller {
	
	private static final Logger log = LoggerFactory.getLogger(MyGroups.class);
	
	public static void myGroups() {
		EntityUser user = Utility.getCurrentUser(session);
		render(user);
	}

	public static void viewGroup(String group) {
		EntityUser user = Utility.getCurrentUser(session);
		EntityGroupXref xref = securityUserGroupCheck(group, user);
		if(xref == null)
			notFound("Either group does not exist or user is not a member of this group");

		EntityGroup groupDbo = xref.getGroup();
		List<SecureResourceGroupXref> resources = groupDbo.getResources();
		render(user, groupDbo, resources);
	}

	public static void groupUsers(String group) {
		EntityUser user = Utility.getCurrentUser(session);

		EntityGroup groupDbo = new EntityGroup();
		List<SecureResourceGroupXref> resources = new ArrayList<SecureResourceGroupXref>();
		if (group != null) {
			EntityGroupXref xref = securityUserGroupCheck(group, user);
			if(!xref.isGroupAdmin())
				viewGroup(group);
			
			groupDbo = xref.getGroup();
			resources = groupDbo.getResources();
		}

		List<EntityGroupXref> users = groupDbo.getChildren();
		for(EntityGroupXref u : users) {
			Entity entity = u.getEntity();
			entity.getName();
			boolean ownRobot = u.isOwnRobot();
			UserType type = entity.getType();
			log.info("name="+entity.getName()+", t="+type+", r="+ownRobot);
		}
		
		String oldGroupName = groupDbo.getName();
		render(user, groupDbo, oldGroupName, resources);
	}
	
	public static void groupResources(String group) {
		EntityUser user = Utility.getCurrentUser(session);

		EntityGroup groupDbo = new EntityGroup();
		List<SecureResourceGroupXref> resources = new ArrayList<SecureResourceGroupXref>();
		if (group != null) {
			EntityGroupXref xref = securityUserGroupCheck(group, user);
			if(!xref.isGroupAdmin())
				viewGroup(group);
			
			groupDbo = xref.getGroup();
			resources = groupDbo.getResources();
		}

		List<EntityGroupXref> users = groupDbo.getChildren();
		for(EntityGroupXref u : users) {
			Entity entity = u.getEntity();
			entity.getName();
		}
		
		String oldGroupName = groupDbo.getName();
		render(user, groupDbo, oldGroupName, resources);
	}
	
	public static void groupProperties(String group) {
		EntityUser user = Utility.getCurrentUser(session);
		
		EntityGroup groupDbo = new EntityGroup();
		List<SecureResourceGroupXref> resources = new ArrayList<SecureResourceGroupXref>();
		if (group != null) {
			EntityGroupXref xref = securityUserGroupCheck(group, user);
			if(!xref.isGroupAdmin())
				viewGroup(group);
			
			groupDbo = xref.getGroup();
			resources = groupDbo.getResources();
		}

		List<EntityGroupXref> users = groupDbo.getChildren();
		for(EntityGroupXref u : users) {
			Entity entity = u.getEntity();
			entity.getName();
		}
		
		String oldGroupName = groupDbo.getName();
		render(user, groupDbo, oldGroupName, resources);
	}
	
	private static EntityGroup adminUserGroupCheck(String group, EntityUser user) {
		EntityGroupXref xref = securityUserGroupCheck(group, user);
		if(xref.isGroupAdmin())
			return xref.getGroup();
		
		notFound("Either you don't have permission or this group does not exist");
		return null;
	}
	
	private static EntityGroupXref securityUserGroupCheck(String group, EntityUser user) {
		if(user.isAdmin()) {
			EntityGroup theGroup = EntityGroup.findByName(NoSql.em(), group);
			for (EntityGroupXref x:theGroup.getChildren()) {
				if (x.isGroupAdmin())
					return x;
			}
		}
		
		EntityGroupXref admin = null;
		EntityGroupXref xref = null;
		// if group is not null, let's check if user has access to it...
		List<EntityGroupXref> groups = user.getParentGroups();
		for (EntityGroupXref r : groups) {
			EntityGroup groupDbo = r.getGroup();
			String name = groupDbo.getName();
			if (name.equals(group)) {
				if(r.isGroupAdmin()) {
					admin = r;
				} else 
					xref = r;
			}
		}
		
		if(admin != null)
			return admin;
		else if(xref != null)
			return xref;

		throw new NotFound("You either don't have access or the group does not exist");
	}
	
	private static void postNewGroup(EntityGroup groupDbo) {
		groupDbo.setId(null); //playframework set this to "" for some reason(need to fix playframework there for Strings
		
		if (log.isInfoEnabled())
			log.info(" grp id=" + groupDbo.getId() + " name=" + groupDbo.getName());
		EntityUser user = Utility.getCurrentUser(session);

		// This is a new group or name change, ensure name does not already
		// exist
		EntityGroup existing = EntityGroup.findByName(NoSql.em(), groupDbo.getName());
		if (existing != null) {
			// If there is already a group and it is not this group(ie. ids are
			// different)
			// we then have a problem...
			validation.addError("groupDbo.name",
					"This group name is in use already");
		}

		if (validation.hasErrors()) {
			render("@editGroup", user, groupDbo);
		}

		String id = UniqueKeyGenerator.generateKey();
		groupDbo.setId(id);
		EntityGroupXref mapping = new EntityGroupXref(groupDbo, user, true, false);
		NoSql.em().put(mapping);
		NoSql.em().put(groupDbo);
		NoSql.em().put(user);

		NoSql.em().flush();

		String group = groupDbo.getName();
		groupUsers(group);
	}
	
	private static void postModifyOldGroup(EntityGroup groupDbo, String oldGroupName) {

		if (log.isInfoEnabled())
			log.info(" grp id=" + groupDbo.getId() + " name=" + groupDbo.getName());
		EntityUser user = Utility.getCurrentUser(session);

		EntityGroup group = adminUserGroupCheck(oldGroupName, user);

		if(!groupDbo.getName().equals(group.getName())) {
			// This is a name change, ensure name does not already exist BUT can exist if id is same(ie. same group)
			EntityGroup existing = EntityGroup.findByName(NoSql.em(), groupDbo.getName());
			if (existing != null && !existing.getId().equals(groupDbo.getId())) {
				// If there is already a group and it is not this group(ie. ids are
				// different)
				// we then have a problem...
				validation.addError("groupDbo.name",
						"This group name is in use already");
			}
		}

		if (validation.hasErrors()) {
			render("@editGroup", user, groupDbo);
		}

		NoSql.em().put(groupDbo);
		NoSql.em().flush();
	}
	
	public static void postGroupUserDelete(String group, String mappingId) {
		EntityUser user = Utility.getCurrentUser(session);
		EntityGroup groupDbo = adminUserGroupCheck(group, user);
		
		EntityGroupXref ref = NoSql.em().find(EntityGroupXref.class, mappingId);
		Entity targetUser = ref.getEntity();
		List<EntityGroupXref> refs = targetUser.getParentGroups();
		
		removeKeyToTableRows2(ref, targetUser);

		refs.remove(ref);
		List<EntityGroupXref> children = groupDbo.getChildren();
		children.remove(ref);
		
		//update group to not have reference to xref
		NoSql.em().put(groupDbo);
		
		if(targetUser.getType() == UserType.ROBOT && ref.isOwnRobot()) {
			//we must remove all other references as well too
			removeFromAll(targetUser);
			NoSql.em().remove(targetUser);
		} else
			NoSql.em().put(targetUser);
		
		NoSql.em().remove(ref);
		NoSql.em().flush();
		
		groupUsers(group);
	}

	private static void removeKeyToTableRows2(EntityGroupXref ref, Entity target) {
		List<SecureResourceGroupXref> resources = ref.getGroup().getResources();
		Counter c = new Counter();
		Set<EntityUser> users = Utility.findAllUsers(target);
		
		for(SecureResourceGroupXref xref : resources) {
			SecureSchema schema = (SecureSchema) xref.getResource();
			Utility.removeKeyToTableRows(schema, users, c);
		}
	}

	private static void removeFromAll(Entity targetUser) {
		List<EntityGroupXref> groupRefs = targetUser.getParentGroups();
		for(EntityGroupXref ref : groupRefs) {
			EntityGroup group = ref.getGroup();
			group.getChildren().remove(ref);
			NoSql.em().put(group);
			NoSql.em().remove(ref);
		}

		List<SecureResourceGroupXref> schemas = targetUser.getSchemas();
		for(SecureResourceGroupXref ref : schemas) {
			SecureResource res = ref.getResource();
			res.getEntitiesWithAccess().remove(ref);
			NoSql.em().put(res);
			NoSql.em().remove(ref);
		}
	}

	public static void postGroupDelete(String group) {
		EntityUser user = Utility.getCurrentUser(session);
		EntityGroup groupDbo = adminUserGroupCheck(group, user);

		//This can take a while...
		removeKeyToTableRows(groupDbo);
		
		List<EntityGroupXref> children = groupDbo.getChildren();
		for(EntityGroupXref ref : children) {
			Entity entity = ref.getEntity();
			entity.getParentGroups().remove(ref);
			NoSql.em().put(entity);
			NoSql.em().remove(ref);
		}
		
		for(EntityGroupXref ref : groupDbo.getParentGroups()) {
			//must be a group if a parent
			EntityGroup entity = ref.getGroup();
			entity.getChildren().remove(ref);
			NoSql.em().put(entity);
			NoSql.em().remove(ref);
		}

		NoSql.em().flush();

		for(SecureResourceGroupXref ref : groupDbo.getResources()) {
			SecureResource resource = ref.getResource();
			resource.getEntitiesWithAccess().remove(ref);
			NoSql.em().put(resource);
			NoSql.em().remove(ref);
		}

		NoSql.em().remove(groupDbo);
		NoSql.em().flush();

		myGroups();
	}

	private static void removeKeyToTableRows(EntityGroup groupDbo) {
		Set<EntityUser> users = new HashSet<EntityUser>();
		List<EntityGroupXref> children = groupDbo.getChildren();
		for(EntityGroupXref ref : children) {
			Entity entity = ref.getEntity();
			if(entity instanceof EntityUser) {
				EntityUser entityUser = (EntityUser) entity;
				users.add(entityUser);
			}
		}

		Counter c = new Counter();
		List<SecureResourceGroupXref> refs = groupDbo.getResources();
		for(SecureResourceGroupXref ref : refs) {
			SecureResource res = ref.getResource();
			removeKeyToTables(res, users, c);
		}
	}

	private static void removeKeyToTables(SecureResource res, Set<EntityUser> users, Counter c) {
		if(res instanceof SecureSchema) {
			SecureSchema db = (SecureSchema) res;
			CursorToMany<SecureTable> cursor = db.getTablesCursor();
			while(cursor.next()) {
				SecureTable t = cursor.getCurrent();
				removeTable(t, users, c);
			}
		} else {
			SecureTable t = (SecureTable) res;
			removeTable(t, users, c);
		}
		NoSql.em().flush();
	}

	private static void removeTable(SecureTable t, Set<EntityUser> users, Counter c) {
		for(EntityUser user : users) {
			String key = KeyToTableName.formKey(t.getName(), user.getUsername(), user.getApiKey());
			EntityUser proxyUser = NoSql.em().getReference(EntityUser.class, key);
			NoSql.em().remove(proxyUser);
			c.increment();
			if(c.getCount() % 100 == 0) {
				log.info("flushing first 100 keytotablename removes");
				NoSql.em().flush();
			}
		}
	}

	public static void postGroup(EntityGroup groupDbo, String oldGroupName) {
		if("".equals(oldGroupName) || oldGroupName == null) {
			postNewGroup(groupDbo);
		} else 
			postModifyOldGroup(groupDbo, oldGroupName);

		String group = groupDbo.getName();
		groupUsers(group);
	}
	
	public static void robotAdd(String group) {
		render();
	}
	
	public static void userAdd(String group, String type) {
		EntityUser user = Utility.getCurrentUser(session);
		adminUserGroupCheck(group, user);
		EntityUser targetUser = new EntityUser();
		boolean isAdmin = false;
		boolean isAdd = true;
		render("@userEdit", group, type, targetUser, isAdmin, isAdd);
	}
	
	public static void userEdit(String group, String type, String username) {
		EntityUser user = Utility.getCurrentUser(session);
		EntityGroup groupDbo = adminUserGroupCheck(group, user);

		EntityUser targetUser = NoSql.em().find(EntityUser.class, username);
		if (targetUser == null) {
			notFound("Page not found");
		}

		EntityGroupXref xref = findUsersXref(targetUser, groupDbo.getId());
		boolean isAdmin = false;
		if(xref == null)
			notFound("Page not found, user probably doesn't belong in this group");
		else
			isAdmin = xref.isGroupAdmin();

		boolean isAdd = false;
		render(group, type, targetUser, isAdmin, isAdd);
	}

	private static EntityGroupXref findUsersXref(EntityUser targetUser,
			String id) {
		//find the group for this user
		for(EntityGroupXref xref : targetUser.getParentGroups()) {
			if(xref.getGroup().getId().equals(id))
				return xref;
		}
		return null;
	}

	public static void postUser(String group, String type, EntityUser targetUser, boolean isAdmin, boolean isAdd) {
		EntityUser user = Utility.getCurrentUser(session);
		EntityGroup groupDbo = adminUserGroupCheck(group, user);
		
		if(isAdd) {
			postAddUser(groupDbo, targetUser, isAdmin, type);
		} else {
			postEditUser(groupDbo, targetUser, isAdmin);
		}
	}

	private static void postAddUser(EntityGroup groupDbo, EntityUser targetUser, boolean isAdmin, String type) {
		boolean isRobot = "robot".equals(type);
		//We are NOT attaching an existing user BUT are creating a brand new user we will attach
		EntityUser user = targetUser;
		if(!isRobot) {
			EntityUser temp = NoSql.em().find(EntityUser.class, targetUser.getId());
			if(temp == null)
				validation.addError("targetUser.username", "The user="+targetUser.getUsername()+" must log into the system before you can add him to a group");
			else
				user = temp;
		}
		
		if(user.getApiKey() == null) { //if not initialized, we need to initialize
			//let's fill in fields that are not in the form...
			user.setApiKey(Utility.getUniqueKey());			
			UserType userType = UserType.lookup(type);
			user.setType(userType);
		}

		if(isRobot) {
			//modify name to be prefixed with robot
			user.setUsername("robot-"+user.getUsername());
		}
		
		if(targetUser.getUsername() == null || "".equals(targetUser.getUsername()))
			validation.addError("targetUser.username", "User name is required");
		
		List<EntityGroupXref> users = groupDbo.getChildren();
		for(EntityGroupXref u : users) {
			if(u.getEntity().getId().equals(user.getId())) {
				validation.addError("targetUser.username", "This user is already in this group");
				break;
			}
		}

		if (validation.hasErrors()) {
			flash.error("You have errors below");
			String group = groupDbo.getName();
			boolean isAdd = true;
			render("@userEdit", group, type, targetUser, isAdmin, isAdd);
			//userAdd(groupDbo.getName(), type);
		}
		
		List<SecureResourceGroupXref> xrefs = groupDbo.getResources();
		for(SecureResourceGroupXref xref : xrefs) {
			SecureResource res = xref.getResource();
			if(res instanceof SecureSchema) {
				MySchemaLogic.addKeyToTableNames((SecureSchema)res, user, xref);
			}
		}
		
		EntityGroupXref mapping = new EntityGroupXref(groupDbo, user, isAdmin, isRobot);

		NoSql.em().put(mapping);
		NoSql.em().put(user);
		NoSql.em().put(groupDbo);

		NoSql.em().flush();

		String group = groupDbo.getName();
		groupUsers(group);
	}

	private static void postEditUser(EntityGroup groupDbo, EntityUser targetUser, boolean isAdmin) {
		EntityUser user = Utility.getCurrentUser(session);
		adminUserGroupCheck(groupDbo.getName(), user);

		EntityGroupXref xref = findUsersXref(targetUser, groupDbo.getId());
		xref.setGroupAdmin(isAdmin);
		NoSql.em().put(xref);
		NoSql.em().flush();
		
		String group = groupDbo.getName();
		groupUsers(group);
	}
}
