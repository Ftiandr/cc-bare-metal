/* <shadow.h> for CC: Bare Metal: password hashes (/etc/shadow, readable by root only), in libbaremetal. */
#ifndef _SHADOW_H
#define _SHADOW_H

#ifdef __cplusplus
extern "C" {
#endif

struct spwd {
    char *sp_namp;   /* user name */
    char *sp_pwdp;   /* crypt() hash; "" no password, "!..." or "*" locked */
    long sp_lstchg;  /* last change, days since 1970 */
    long sp_min;
    long sp_max;
    long sp_warn;
    long sp_inact;
    long sp_expire;
    unsigned long sp_flag;
};

struct spwd *getspnam(const char *name);

#ifdef __cplusplus
}
#endif

#endif
