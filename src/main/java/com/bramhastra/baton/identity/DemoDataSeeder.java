package com.bramhastra.baton.identity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.bramhastra.baton.common.Sensitivity;
import com.bramhastra.baton.tenant.TenantContext;
import com.bramhastra.baton.tenant.TenantRegistry;

/**
 * Seeds the synthetic "Project Annapurna" team on an empty database.
 * All accounts are fictional test personas on the reserved .test domain.
 */
@Component
@ConditionalOnProperty(name = "baton.demo.seed", havingValue = "true")
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    public static final String PROJECT_NAME = "Project Annapurna";

    private final AppUserRepository users;
    private final UserGroupRepository groups;
    private final ProjectRepository projects;
    private final ProjectMemberRepository members;
    private final PasswordEncoder passwords;
    private final String demoPassword;
    private final TenantRegistry tenants;
    private final TransactionTemplate tx;

    public DemoDataSeeder(AppUserRepository users, UserGroupRepository groups, ProjectRepository projects,
                          ProjectMemberRepository members, PasswordEncoder passwords,
                          @Value("${baton.demo.password}") String demoPassword, TenantRegistry tenants,
                          PlatformTransactionManager txManager) {
        this.tenants = tenants;
        this.tx = new TransactionTemplate(txManager);
        this.users = users;
        this.groups = groups;
        this.projects = projects;
        this.members = members;
        this.passwords = passwords;
        this.demoPassword = demoPassword;
    }

    record Persona(String email, String name, OrgRole orgRole, Sensitivity clearance, Set<String> groups,
                   ProjectRole projectRole) {
    }

    static final List<Persona> PERSONAS = List.of(
            new Persona("admin@annapurna.test", "Org Admin", OrgRole.ADMIN, Sensitivity.INTERNAL,
                    Set.of(), null),
            new Persona("neha@annapurna.test", "Neha Kulkarni", OrgRole.MEMBER, Sensitivity.RESTRICTED,
                    Set.of("engineering", "operations"), ProjectRole.PROJECT_LEAD),
            new Persona("priya@annapurna.test", "Priya Sharma", OrgRole.MEMBER, Sensitivity.CONFIDENTIAL,
                    Set.of("engineering"), ProjectRole.MEMBER),
            new Persona("arjun@annapurna.test", "Arjun Mehta", OrgRole.MEMBER, Sensitivity.CONFIDENTIAL,
                    Set.of("engineering"), ProjectRole.MEMBER),
            new Persona("rahul@annapurna.test", "Rahul Desai", OrgRole.MEMBER, Sensitivity.INTERNAL,
                    Set.of("design"), ProjectRole.MEMBER));

    @Override
    public void run(ApplicationArguments args) {
        // Seed the demo workspace only (Baton's own database), never a company's.
        tenants.bySlug(TenantRegistry.DEMO_SLUG).ifPresent(demo ->
                TenantContext.run(demo, () -> tx.executeWithoutResult(s -> seed())));
    }

    private void seed() {
        if (users.count() > 0) {
            return;
        }
        Map<String, UserGroup> groupByName = new HashMap<>();
        for (String[] g : new String[][] {
                {"engineering", "Engineers: code, deploys, infrastructure"},
                {"operations", "Vendors, accounts, budgets"},
                {"design", "UI/UX volunteers"}}) {
            groupByName.put(g[0], groups.save(new UserGroup(g[0], g[1])));
        }

        Project project = new Project();
        project.setName(PROJECT_NAME);
        project.setDescription("Campus canteen pre-order app built by the college tech club (synthetic demo data).");
        project = projects.save(project);

        String hash = passwords.encode(demoPassword);
        for (Persona p : PERSONAS) {
            AppUser u = new AppUser();
            u.setEmail(p.email());
            u.setDisplayName(p.name());
            u.setOrgRole(p.orgRole());
            u.setClearance(p.clearance());
            u.setPasswordHash(hash);
            p.groups().forEach(g -> u.getGroups().add(groupByName.get(g)));
            AppUser saved = users.save(u);
            if (p.projectRole() != null) {
                members.save(new ProjectMember(project.getId(), saved.getId(), p.projectRole()));
            }
            if (p.projectRole() == ProjectRole.PROJECT_LEAD && project.getCreatedBy() == null) {
                project.setCreatedBy(saved.getId());
            }
        }
        log.info("Seeded demo project '{}' with {} personas", PROJECT_NAME, PERSONAS.size());
    }
}
