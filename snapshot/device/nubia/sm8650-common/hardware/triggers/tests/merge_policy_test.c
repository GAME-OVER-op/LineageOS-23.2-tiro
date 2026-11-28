/* SPDX-License-Identifier: Apache-2.0 */
/* Host regression test: include the daemon, replace device opens, pipe evdev reports. */
#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <fcntl.h>

static int no_device_open(const char *path, int flags, ...) {
    (void)path;
    (void)flags;
    errno=ENOENT;
    return -1;
}

#define open no_device_open
#define main nubia_trigger_daemon_main
#include "../main.c"
#undef main
#undef open

int __android_log_print(int priority,const char *tag,const char *fmt,...) {
    (void)priority;
    (void)tag;
    (void)fmt;
    return 0;
}

static void reset_controller(bool grabbed) {
    g=(struct controller){
        .ep=-1,.notify_fd=-1,.notify_wd=-1,.ufd=-1,
        .touch={.fd=-1},.left={.fd=-1},.right={.fd=-1},
        .active=grabbed,.slot_max=9,.virt_slots={10,11},
    };
    for(int i=0;i<MAX_SLOTS;i++)g.tid[i]=-1;
}

static struct config game_config(void) {
    return (struct config){
        .enabled=true,.screen=true,.rotation=1,.foreground="test.game",.profiles_n=1,
        .profiles={{.pkg="test.game",.rotation=1,.l={true,true,.8f,.25f}}},
    };
}

static bool drain_btn_touch(int fd) {
    struct input_event events[32];
    ssize_t n=read(fd,events,sizeof(events));
    assert(n>0 && n%(ssize_t)sizeof(events[0])==0);
    int value=-1;
    for(size_t i=0;i<(size_t)n/sizeof(events[0]);i++) {
        if(events[i].type==EV_KEY && events[i].code==BTN_TOUCH)value=events[i].value;
    }
    assert(value==0||value==1);
    return value==1;
}

static void test_navigation_and_rotation_keep_the_same_device(void) {
    int input[2];
    assert(pipe(input)==0);
    reset_controller(true);
    g.ufd=input[1];
    struct config c=game_config();
    apply_config(&c,true);
    assert(binding_active(g.selected[0]));
    g.tid[3]=42;
    g.finger_count=1;
    g.sar_key[0]=g.virt_down[0]=true;
    strcpy(c.foreground,"test.launcher");
    apply_config(&c,true);
    assert(g.active && !g.pending_stop && g.ufd==input[1]);
    assert(g.tid[3]==42 && !g.virt_down[0] && g.sar_rearm[0]);
    assert(drain_btn_touch(input[0]));

    g.tid[3]=-1;
    g.finger_count=0;
    strcpy(c.foreground,"test.game");
    c.rotation=c.profiles[0].rotation=3;
    apply_config(&c,true);
    assert(g.active && g.ufd==input[1] && g.rotation==3 && binding_active(g.selected[0]));
    c.editing=true;
    apply_config(&c,true);
    assert(g.active && g.ufd==input[1] && !binding_active(g.selected[0]));
    close(input[0]);
    close(input[1]);
}

static void test_disable_waits_for_fingers_but_can_be_canceled(void) {
    reset_controller(false);
    struct config c={0};
    apply_config(&c,false);
    assert(!g.pending_stop);  // No runtime.conf yet at boot, no fd to query.

    reset_controller(true);
    c=game_config();
    apply_config(&c,true);
    g.tid[0]=5;
    g.finger_count=1;
    c.screen=false;
    apply_config(&c,true);
    assert(g.active && g.pending_stop && !binding_active(g.selected[0]));

    c.screen=true;
    apply_config(&c,true);
    assert(g.active && !g.pending_stop && binding_active(g.selected[0]));
    c.enabled=false;
    apply_config(&c,true);
    assert(g.active && g.pending_stop);
    g.tid[0]=-1;
    g.finger_count=0;
    apply_config(&c,true);
    assert(!g.active && !g.pending_stop);
}

static void test_trigger_release_does_not_publish_an_unfinished_finger_frame(void) {
    int source[2],target[2];
    assert(pipe(source)==0 && pipe(target)==0);
    reset_controller(true);
    g.touch.fd=source[0];
    g.ufd=target[1];
    g.virt_down[0]=true;

    const struct input_event finger[]={{.type=EV_ABS,.code=ABS_MT_SLOT,.value=4},
        {.type=EV_ABS,.code=ABS_MT_TRACKING_ID,.value=9}};
    assert(write(source[1],finger,sizeof(finger))==(ssize_t)sizeof(finger));
    handle_touch();
    assert(g.tid[4]==9 && g.finger_count==0);
    release_virtual(0);
    assert(!drain_btn_touch(target[0]));

    const struct input_event end={.type=EV_SYN,.code=SYN_REPORT};
    assert(write(source[1],&end,sizeof(end))==(ssize_t)sizeof(end));
    handle_touch();
    assert(g.finger_count==1 && drain_btn_touch(target[0]));
    close(source[0]);
    close(source[1]);
    close(target[0]);
    close(target[1]);
}

int main(void) {
    test_navigation_and_rotation_keep_the_same_device();
    test_disable_waits_for_fingers_but_can_be_canceled();
    test_trigger_release_does_not_publish_an_unfinished_finger_frame();
    puts("3 touch merge regression cases passed");
    return 0;
}
