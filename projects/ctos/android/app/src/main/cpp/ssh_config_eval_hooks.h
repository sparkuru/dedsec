#ifndef CTOS_SSH_CONFIG_EVAL_HOOKS_H
#define CTOS_SSH_CONFIG_EVAL_HOOKS_H
/* Included after all upstream headers, in the evaluator parser TU only. */
#include "ssh_config_eval.h"

int ctos_config_reject(unsigned int, int);
int ctos_config_check(void);
int ctos_config_catalog_mode(void);
int ctos_config_audit_mode(void);
int ctos_config_scan_mode(void);
int ctos_config_resource_mode(void);
int ctos_config_resource_line(unsigned int, int, char **);
int ctos_config_catalog_line(int, int, char **);
int ctos_config_catalog_defer(const char *);
int ctos_config_options_check(const Options *);
void ctos_config_mark_options(const Options *);
void ctos_config_defaults_error(const char *);
int ctos_config_enter_file(const char *, int);
void ctos_config_leave_file(void);
ssize_t ctos_config_getline(char **, size_t *, FILE *);
FILE *ctos_config_fopen(const char *, const char *);
int ctos_config_fclose(FILE *);
int ctos_config_glob(const char *, int, int (*)(const char *, int), glob_t *);
int ctos_config_port(const char *);
char *ctos_config_percent(const char *, ...);
char *ctos_config_dollar(int *, const char *);
char *ctos_config_include(const char *, Options *, const char *, const char *, int);
const char *ctos_config_remote_user(const Options *);
int ctos_config_kex_valid(const char *);
void ctos_config_array(const char *, int, const char *, char ***, u_int *, const char *);
void ctos_config_quiet(const char *, const char *, int, int, LogLevel, const char *, const char *, ...);
int ctos_config_jump_component(const char *, const char *);
int ctos_config_parse_user_host_port(const char *, char **, char **, int *);
int ctos_config_parse_uri(const char *, const char *, char **, char **, int *, char **);

#define sshlog ctos_config_quiet
#define fopen ctos_config_fopen
#define fclose ctos_config_fclose
#define getline ctos_config_getline
#undef glob
#define glob ctos_config_glob
#define a2port ctos_config_port
#define percent_expand ctos_config_percent
#define percent_dollar_expand ctos_config_percent
#define dollar_expand ctos_config_dollar
#define kex_names_valid ctos_config_kex_valid
#define opt_array_append ctos_config_array
#define parse_user_host_port ctos_config_parse_user_host_port
#define parse_uri ctos_config_parse_uri

/* Do not interpose any symbol used by existing production sessions. */
#define kex_default_pk_alg ctos_config_upstream_kex_default_pk_alg
#define ssh_connection_hash ctos_config_upstream_ssh_connection_hash
#define initialize_options ctos_config_upstream_initialize_options
#define fill_default_options ctos_config_upstream_fill_default_options
#define fill_default_options_for_canonicalization ctos_config_upstream_fill_default_options_for_canonicalization
#define free_options ctos_config_upstream_free_options
#define process_config_line ctos_config_upstream_process_config_line
#define read_config_file ctos_config_upstream_read_config_file
#define parse_forward ctos_config_upstream_parse_forward
#define ssh_valid_hostname ctos_config_upstream_ssh_valid_hostname
#define ssh_valid_ruser ctos_config_upstream_ssh_valid_ruser
#define parse_jump ctos_config_upstream_parse_jump
#define parse_ssh_uri ctos_config_upstream_parse_ssh_uri
#define default_ssh_port ctos_config_upstream_default_ssh_port
#define option_clear_or_none ctos_config_upstream_option_clear_or_none
#define config_has_permitted_cnames ctos_config_upstream_config_has_permitted_cnames
#define add_local_forward ctos_config_upstream_add_local_forward
#define add_remote_forward ctos_config_upstream_add_remote_forward
#define add_identity_file ctos_config_upstream_add_identity_file
#define add_certificate_file ctos_config_upstream_add_certificate_file

/* Prototypes must also be namespaced, including calls before definitions. */
const char *ctos_config_upstream_kex_default_pk_alg(void);
char *ctos_config_upstream_ssh_connection_hash(const char *, const char *, const char *, const char *, const char *);
void ctos_config_upstream_initialize_options(Options *);
int ctos_config_upstream_fill_default_options(Options *);
void ctos_config_upstream_fill_default_options_for_canonicalization(Options *);
void ctos_config_upstream_free_options(Options *);
int ctos_config_upstream_parse_forward(struct Forward *, const char *, int, int);
int ctos_config_upstream_parse_jump(const char *, Options *, int, int);
int ctos_config_upstream_parse_ssh_uri(const char *, char **, char **, int *);
int ctos_config_upstream_default_ssh_port(void);
int ctos_config_upstream_option_clear_or_none(const char *);
int ctos_config_upstream_config_has_permitted_cnames(Options *);
int ctos_config_upstream_ssh_valid_hostname(const char *);
int ctos_config_upstream_ssh_valid_ruser(const char *);
#endif
