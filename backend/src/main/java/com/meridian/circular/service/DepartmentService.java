package com.meridian.circular.service;

import com.meridian.circular.domain.PlatformAdmin;
import com.meridian.circular.domain.Tenant;
import com.meridian.circular.dto.Dtos.CreateDepartmentRequest;
import com.meridian.circular.dto.Dtos.DepartmentDto;
import com.meridian.circular.dto.Dtos.UpdateDepartmentRequest;
import com.meridian.circular.repo.TenantRepository;
import com.meridian.circular.web.ApiException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Department (tenant) administration for super admins: list, create — which
 * provisions a brand-new Postgres schema via {@code provision_tenant} — and
 * rename / (de)activate. Hard deletion (dropping a schema) is intentionally not
 * supported in v1.
 *
 * <p>Create runs as one transaction: insert the tenant, seed its
 * {@code source_tenant} fan-out (so scraped circulars reach it), call
 * {@code provision_tenant} to build the schema + working tables and copy the
 * source-tagged canonical circulars, then seed default config + reminders.
 */
@Service
public class DepartmentService {

    private static final Logger log = LoggerFactory.getLogger(DepartmentService.class);

    /** Department code: uppercase, becomes the (lowercased) schema name. */
    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,39}$");

    private final TenantRepository tenants;

    @PersistenceContext
    private EntityManager em;

    public DepartmentService(TenantRepository tenants) {
        this.tenants = tenants;
    }

    public List<DepartmentDto> list() {
        return tenants.findAll().stream()
                .sorted(Comparator.comparing(t -> t.tenantId))
                .map(DepartmentService::toDto)
                .toList();
    }

    @Transactional
    public DepartmentDto create(CreateDepartmentRequest req, PlatformAdmin actor) {
        if (req == null || req.code() == null || req.name() == null || req.name().isBlank()) {
            throw ApiException.badRequest("Department code and name are required.");
        }
        String code = req.code().trim().toUpperCase();
        if (!CODE.matcher(code).matches()) {
            throw ApiException.badRequest(
                    "Code must be 2-40 chars, start with a letter, and contain only A-Z, 0-9, _.");
        }
        if (tenants.findAll().stream().anyMatch(t -> code.equalsIgnoreCase(t.code))) {
            throw ApiException.badRequest("A department with this code already exists.");
        }
        String schema = code.toLowerCase();
        if (schemaExists(schema)) {
            throw ApiException.badRequest("A schema named '" + schema + "' already exists.");
        }

        int tenantId = nextTenantId();
        // 1. tenant row
        em.createNativeQuery(
                "INSERT INTO public.tenant (tenant_id, code, name, is_active) "
                + "VALUES (:id, :code, :name, TRUE)")
                .setParameter("id", tenantId)
                .setParameter("code", code)
                .setParameter("name", req.name().trim())
                .executeUpdate();

        // 2. source_tenant fan-out (NSE + EMAIL feed every department; MANUAL
        //    routes to the uploader's workspace) — BEFORE provisioning so the
        //    canonical-circular copy includes these sources.
        em.createNativeQuery(
                "INSERT INTO public.source_tenant (source_id, tenant_id) "
                + "SELECT s, :id FROM (VALUES ('NSE'), ('EMAIL')) AS v(s) "
                + "ON CONFLICT (source_id, tenant_id) DO NOTHING")
                .setParameter("id", tenantId)
                .executeUpdate();

        // 3. provision the schema + working tables + copy source-tagged circulars
        em.createNativeQuery("SELECT provision_tenant(:code)")
                .setParameter("code", code)
                .getResultList();

        // 4. default config + reminder intervals for the new department
        seedDefaults(tenantId);

        log.info("Department created — code={} schema={} tenantId={} by={}",
                code, schema, tenantId, actor.username);
        return new DepartmentDto(tenantId, code, req.name().trim(), true);
    }

    @Transactional
    public DepartmentDto update(Integer id, UpdateDepartmentRequest req, PlatformAdmin actor) {
        Tenant t = tenants.findById(id)
                .orElseThrow(() -> ApiException.notFound("Department"));
        if (req.name() != null && !req.name().isBlank()) {
            t.name = req.name().trim();
        }
        if (req.isActive() != null) {
            t.isActive = req.isActive();
        }
        tenants.save(t);
        log.info("Department updated — tenantId={} by={}", id, actor.username);
        return toDto(t);
    }

    // ---- helpers -----------------------------------------------------------

    private int nextTenantId() {
        Object max = em.createNativeQuery(
                "SELECT COALESCE(MAX(tenant_id), 0) + 1 FROM public.tenant").getSingleResult();
        return ((Number) max).intValue();
    }

    private boolean schemaExists(String schema) {
        return !em.createNativeQuery(
                "SELECT 1 FROM information_schema.schemata WHERE schema_name = :s")
                .setParameter("s", schema)
                .getResultList().isEmpty();
    }

    private void seedDefaults(int tenantId) {
        em.createNativeQuery(
                "INSERT INTO public.app_config (tenant_id, config_key, value_json, description) "
                + "VALUES "
                + "(:id, 'reminder.pause_on_closed', 'true'::jsonb, "
                + "'Whether reminders pause once a circular is CLOSED.'), "
                + "(:id, 'reminder.send_window', "
                + "'{\"start_hour\":9,\"end_hour\":19,\"timezone\":\"Asia/Kolkata\"}'::jsonb, "
                + "'Time-of-day window in which scheduled reminders may be sent.') "
                + "ON CONFLICT (tenant_id, config_key) DO NOTHING")
                .setParameter("id", tenantId)
                .executeUpdate();

        em.createNativeQuery(
                "INSERT INTO public.reminder_interval "
                + "(days_after_action, label, kind, is_active, sort_order, tenant_id) "
                + "SELECT v.days, v.label, v.kind, v.active, v.sort, :id FROM (VALUES "
                + "( 3, 'T+3 days  - first nudge',  'POST_ACTION', TRUE,  10), "
                + "( 7, 'T+7 days  - second nudge', 'POST_ACTION', TRUE,  20), "
                + "(14, 'T+14 days - escalation',   'POST_ACTION', TRUE,  30), "
                + "(30, 'T+30 days - final notice', 'POST_ACTION', TRUE,  40) "
                + ") AS v(days, label, kind, active, sort) "
                + "ON CONFLICT (tenant_id, kind, days_after_action) DO NOTHING")
                .setParameter("id", tenantId)
                .executeUpdate();
    }

    private static DepartmentDto toDto(Tenant t) {
        return new DepartmentDto(t.tenantId, t.code, t.name, t.isActive);
    }
}
