#ifndef CTOS_SSH_CONFIG_EVAL_H
#define CTOS_SSH_CONFIG_EVAL_H

#include "ssh_view_handle.h"
#include <sys/types.h>

#define CTOS_SSH_CONFIG_NAME_BYTES 255U
#define CTOS_SSH_CONFIG_LINE_BYTES 8192U
#define CTOS_SSH_CONFIG_LINES 16384U
#define CTOS_SSH_CONFIG_LIST 256U
#define CTOS_SSH_CONFIG_DEPTH 8U
#define CTOS_SSH_CONFIG_JUMPS 8U
#define CTOS_SSH_CONFIG_FORWARDS 32U
#define CTOS_SSH_CONFIG_SOURCE_BYTES 8455704U
#define CTOS_SSH_CONFIG_EDGES 1024U
enum ctos_ssh_config_mode { CTOS_CONFIG_EFFECTIVE, CTOS_CONFIG_CATALOG, CTOS_CONFIG_AUDIT, CTOS_CONFIG_RESOURCE_AUDIT };

enum ctos_ssh_config_reason {
    CTOS_CONFIG_OK, CTOS_CONFIG_INPUT, CTOS_CONFIG_PATH, CTOS_CONFIG_LIMIT,
    CTOS_CONFIG_COMMAND, CTOS_CONFIG_TRUST, CTOS_CONFIG_EXTERNAL,
    CTOS_CONFIG_UNSUPPORTED, CTOS_CONFIG_USER_REQUIRED, CTOS_CONFIG_EXPANSION,
    CTOS_CONFIG_IO, CTOS_CONFIG_LIFETIME, CTOS_CONFIG_DEFAULTS, CTOS_CONFIG_REFUSED
};
enum ctos_ssh_config_state {
    CTOS_CONFIG_NEW, CTOS_CONFIG_RUNNING, CTOS_CONFIG_READY,
    CTOS_CONFIG_FAILED, CTOS_CONFIG_CLOSING, CTOS_CONFIG_CLOSED, CTOS_CONFIG_LOADING
};
struct ctos_ssh_config_error {
    unsigned int reason, object, line;
    int error_number;
};
struct ctos_ssh_config_request {
    const char *alias;
    const char *remote_user; /* Optional explicit override, never local UID. */
    const char *remote_user_fallback; /* Match/%r only, does not override User. */
    unsigned int port; /* Zero means no command-line override. */
    const char *local_user;
    const char *local_hostname;
    uid_t local_uid;
    const char *tag;
    const char *remote_command; /* Optional Match context, not an execution grant. */
};
struct ctos_ssh_config_summary {
    char alias[CTOS_SSH_CONFIG_NAME_BYTES + 1];
    char hostname[CTOS_SSH_CONFIG_NAME_BYTES + 1];
    char user[CTOS_SSH_CONFIG_NAME_BYTES + 1];
    unsigned int user_required, port, identities, certificates;
    unsigned int local_forwards, remote_forwards, has_jump, has_remote_command;
    int request_tty, session_type;
    /* Full Options retains raw path/session/forward expansions. It is not a
     * launch plan. A connector must finalize/validate every retained field. */
    unsigned int has_raw_fields;
};
#define CTOS_SSH_CONFIG_HOSTFILES 32U
struct ctos_ssh_config_direct_plan {
    struct ctos_ssh_config_summary summary;
    unsigned int identities_only, pubkey_authentication, password_authentication;
    unsigned int connect_timeout_millis, strict_host_key_checking;
    unsigned int identity_count, hostfile_count;
    char identity_files[CTOS_SSH_CONFIG_LIST][513];
    char user_hostfiles[CTOS_SSH_CONFIG_HOSTFILES][513];
};
struct ctos_ssh_config_catalog_entry {
    unsigned int object, line;
    char alias[CTOS_SSH_CONFIG_NAME_BYTES + 1];
};
struct ctos_ssh_config_catalog_site { unsigned int object, line; };
struct ctos_ssh_config_catalog {
    unsigned int omitted_selectors, has_match, scanned_files, alias_count, deferred_count;
    struct ctos_ssh_config_catalog_entry entries[CTOS_SSH_CONFIG_LIST];
    struct ctos_ssh_config_catalog_site deferred[CTOS_SSH_CONFIG_LIST];
};
struct ctos_ssh_config_edge { unsigned int parent, line, child; };
struct ctos_ssh_config_audit {
    const struct ctos_ssh_config_catalog *catalog;
    unsigned int edge_count;
    struct ctos_ssh_config_edge edges[CTOS_SSH_CONFIG_EDGES];
};
#define CTOS_SSH_CONFIG_RESOURCES 1024U
struct ctos_ssh_config_resource {
    unsigned int kind, parent, line, argument, state, target;
};
struct ctos_ssh_config_resource_audit {
    const struct ctos_ssh_config_audit *audit;
    unsigned int resource_count;
    struct ctos_ssh_config_resource resources[CTOS_SSH_CONFIG_RESOURCES];
};
struct ctos_ssh_config_eval;
struct ctos_ssh_config_file {
    unsigned int type;
    struct ctos_ssh_slice path, body;
};
/* Complete ordered manifest, zero-based selected CONFIG root, original absolute
 * CLOCK_BOOTTIME milliseconds (future, <=60 seconds). Only CONFIG bodies are
 * accepted; every other type is path metadata with body={NULL,0}. Caller keeps
 * inputs stable for this call, then may erase/free them. No vault capability is
 * acquired. All records/paths/CONFIG bytes are independently owned on success.
 *
 * On -1, *out is normally NULL. If resource cleanup fails after allocation,
 * *out is a CLOSING owner: caller MUST retry close then destroy. It cannot run
 * or publish results. Caller inputs are never wiped, even on failure. */
int ctos_ssh_config_eval_open_detached(const struct ctos_ssh_config_file *,
    unsigned int, unsigned int, uint64_t, const struct ctos_ssh_config_request *,
    struct ctos_ssh_config_eval **);
int ctos_ssh_config_eval_open_catalog_detached(const struct ctos_ssh_config_file *,
    unsigned int, unsigned int, uint64_t, struct ctos_ssh_config_eval **);
/* Internal staged owner. Prepare publishes only fixed metadata; buffer records
 * cleanup ownership before protection; complete adopts CSDI in place. Any
 * failure leaves the owner reachable and closable, never internally freed. */
int ctos_ssh_config_eval_prepare_detached(int, uint64_t, struct ctos_ssh_config_eval **);
int ctos_ssh_config_eval_prepare_direct_detached(uint64_t, struct ctos_ssh_config_eval **);
int ctos_ssh_config_eval_prepare_audit_detached(uint64_t, struct ctos_ssh_config_eval **);
int ctos_ssh_config_eval_prepare_resource_audit_detached(uint64_t, struct ctos_ssh_config_eval **);
int ctos_ssh_config_eval_detached_buffer(struct ctos_ssh_config_eval *, size_t, unsigned char **);
int ctos_ssh_config_eval_loading_live(const struct ctos_ssh_config_eval *);
int ctos_ssh_config_eval_complete_detached(struct ctos_ssh_config_eval *, unsigned int,
    const struct ctos_ssh_config_request *);
int ctos_ssh_config_eval_usage(const struct ctos_ssh_config_eval *, uint32_t [3]);
int ctos_ssh_config_eval_open(struct ctos_ssh_view_borrow *,
    const struct ctos_ssh_config_request *, struct ctos_ssh_config_eval **);
int ctos_ssh_config_eval_open_catalog(struct ctos_ssh_view_borrow *, struct ctos_ssh_config_eval **);
/* Borrowed historical metadata; never retain the pointer after close. */
int ctos_ssh_config_eval_catalog(const struct ctos_ssh_config_eval *, const struct ctos_ssh_config_catalog **);
int ctos_ssh_config_eval_audit(const struct ctos_ssh_config_eval *, const struct ctos_ssh_config_audit **);
int ctos_ssh_config_eval_resource_audit(const struct ctos_ssh_config_eval *, const struct ctos_ssh_config_resource_audit **);
int ctos_ssh_config_eval_run(struct ctos_ssh_config_eval *, struct ctos_ssh_config_error *);
int ctos_ssh_config_eval_summary(const struct ctos_ssh_config_eval *, struct ctos_ssh_config_summary *);
int ctos_ssh_config_eval_direct_plan(const struct ctos_ssh_config_eval *, const struct ctos_ssh_config_direct_plan **);
/* Native internal ABI only: include upstream readconf.h before this header to
 * obtain the typed full-Options getter. No pointer may survive close. */
#ifdef READCONF_H
int ctos_ssh_config_eval_options(const struct ctos_ssh_config_eval *, const Options **);
#endif
void ctos_ssh_config_eval_cancel(struct ctos_ssh_config_eval *);
int ctos_ssh_config_eval_close(struct ctos_ssh_config_eval *);
int ctos_ssh_config_eval_destroy(struct ctos_ssh_config_eval *);
#endif
