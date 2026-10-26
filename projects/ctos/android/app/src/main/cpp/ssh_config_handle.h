#ifndef CTOS_SSH_CONFIG_HANDLE_H
#define CTOS_SSH_CONFIG_HANDLE_H

#include "ssh_config_eval.h"
#include <stddef.h>
#include <stdint.h>

#define CTOS_SSH_CONFIG_HANDLE_SLOTS 64U
#define CTOS_SSH_CONFIG_REQUEST_BYTES 9766U
#define CTOS_SSH_CONFIG_SUMMARY_BYTES 829U
#define CTOS_SSH_CONFIG_RUN_VALUES 5U
#define CTOS_SSH_CONFIG_CATALOG_BYTES 70424U
#define CTOS_SSH_CONFIG_RESOURCE_BYTES 107328U
#define CTOS_SSH_CONFIG_AUDIT_BYTES 82732U
#define CTOS_SSH_CONFIG_DIRECT_BYTES (48U + 3U * 259U + (256U + 32U) * 516U)

/* Positive signed-64-bit opaque identity; never an address or reusable slot. */
typedef uint64_t ctos_ssh_config_token;
int ctos_ssh_config_handle_open(ctos_ssh_view_token, const unsigned char *, size_t,
    ctos_ssh_config_token *);
int ctos_ssh_config_handle_open_catalog(ctos_ssh_view_token, ctos_ssh_config_token *);
int ctos_ssh_config_handle_reserve_detached(int, ctos_ssh_config_token *);
int ctos_ssh_config_handle_reserve_direct(ctos_ssh_config_token *);
int ctos_ssh_config_handle_reserve_audit(ctos_ssh_config_token *);
int ctos_ssh_config_handle_reserve_resource_audit(ctos_ssh_config_token *);
int ctos_ssh_config_handle_audit(ctos_ssh_config_token,
    unsigned char [CTOS_SSH_CONFIG_AUDIT_BYTES], size_t *);
int ctos_ssh_config_handle_audit_live(ctos_ssh_config_token);
int ctos_ssh_config_handle_resource_audit(ctos_ssh_config_token, unsigned char [CTOS_SSH_CONFIG_RESOURCE_BYTES], size_t *);
int ctos_ssh_config_handle_resource_audit_live(ctos_ssh_config_token);
/* Trusted synchronous C array copier only, called outside the registry mutex.
 * request=1 selects bounded CSEQ; request=0 selects protected CSDI storage.
 * Return -1/errno on copy failure (including a pending JNI exception). The
 * already published token retains every cleanup resource on all failures. */
typedef int (*ctos_ssh_config_copy)(void *, int, unsigned char *, size_t);
int ctos_ssh_config_handle_load_detached(ctos_ssh_config_token, size_t,
    unsigned int, uint64_t, int, size_t, ctos_ssh_config_copy, void *);
int ctos_ssh_config_handle_usage(ctos_ssh_config_token, uint32_t [3]);
int ctos_ssh_config_handle_catalog(ctos_ssh_config_token,
    unsigned char [CTOS_SSH_CONFIG_CATALOG_BYTES], size_t *);
int ctos_ssh_config_handle_catalog_live(ctos_ssh_config_token);
/* Expected native admission failures are returned as status=2, with reason=0. */
int ctos_ssh_config_handle_run(ctos_ssh_config_token, int32_t [CTOS_SSH_CONFIG_RUN_VALUES]);
int ctos_ssh_config_handle_summary(ctos_ssh_config_token,
    unsigned char [CTOS_SSH_CONFIG_SUMMARY_BYTES], size_t *);
/* Revalidate after JNI allocation/array writes, before publishing the result. */
int ctos_ssh_config_handle_summary_live(ctos_ssh_config_token);
int ctos_ssh_config_handle_direct_plan(ctos_ssh_config_token,
    unsigned char [CTOS_SSH_CONFIG_DIRECT_BYTES], size_t *);
int ctos_ssh_config_handle_direct_plan_live(ctos_ssh_config_token);
int ctos_ssh_config_handle_cancel(ctos_ssh_config_token);
int ctos_ssh_config_handle_close(ctos_ssh_config_token);
int ctos_ssh_config_handle_destroy(ctos_ssh_config_token);

#ifdef CTOS_CONFIG_HANDLE_TEST
/* Only linked into independent native tests, never the production library. */
int ctos_ssh_config_handle_test_seed_next(uint64_t);
#endif
#endif
