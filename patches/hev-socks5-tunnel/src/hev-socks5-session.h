/*
 ============================================================================
 Name        : hev-socks5-session.h
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2017 - 2023 hev
 Description : Socks5 Session
 ============================================================================
 */

#ifndef __HEV_SOCKS5_SESSION_H__
#define __HEV_SOCKS5_SESSION_H__

#include <stdatomic.h>
#include <stdint.h>

#include <hev-task.h>

#include "hev-list.h"

#define HEV_SOCKS5_SESSION(p) ((HevSocks5Session *)p)
#define HEV_SOCKS5_SESSION_IFACE(p) ((HevSocks5SessionIface *)p)
#define HEV_SOCKS5_SESSION_TYPE (hev_socks5_session_iface ())

#define HEV_SESSION_PROTO_TCP 6
#define HEV_SESSION_PROTO_UDP 17

typedef void HevSocks5Session;
typedef struct _HevSocks5SessionData HevSocks5SessionData;
typedef struct _HevSocks5SessionIface HevSocks5SessionIface;

struct _HevSocks5SessionData
{
    HevListNode node;
    HevTask *task;
    HevSocks5Session *self;
    /* 连接观测信息（供 JNI 枚举当前会话） */
    int proto;             /* IPPROTO_TCP / IPPROTO_UDP */
    void *pcb;             /* struct tcp_pcb * / udp_pcb * */
    int64_t created_ms;    /* 会话创建的 wall-clock 毫秒 */
    _Atomic uint64_t upload_bytes;   /* App -> 远端（仅 payload） */
    _Atomic uint64_t download_bytes; /* 远端 -> App（仅 payload） */
};

struct _HevSocks5SessionIface
{
    void (*splicer) (HevSocks5Session *self);
    HevTask *(*get_task) (HevSocks5Session *self);
    void (*set_task) (HevSocks5Session *self, HevTask *task);
    HevListNode *(*get_node) (HevSocks5Session *self);
};

void *hev_socks5_session_iface (void);

void hev_socks5_session_run (HevSocks5Session *self);
void hev_socks5_session_terminate (HevSocks5Session *self);

void hev_socks5_session_set_task (HevSocks5Session *self, HevTask *task);
HevListNode *hev_socks5_session_get_node (HevSocks5Session *self);

int hev_socks5_session_bind (HevSocks5 *self, int fd,
                             const struct sockaddr *dest);

#endif /* __HEV_SOCKS5_SESSION_H__ */
