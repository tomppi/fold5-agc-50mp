#!/system/bin/sh
# q5q_cameraserver_hr v8
#
# post-fs-data.sh stages the patched binary on a private tmpfs and bind-mounts it over
# /system/bin/cameraserver before init starts class main. The overlayfs metamodule mounts
# its own /system overlay in the same boot stage, and a later mount at /system hides an
# earlier bind mount underneath it - that is what killed v4. So verify here which binary
# actually ended up running and, if it is not the patched one, stage again on top of
# everything and restart the service.
MODDIR=${0%/*}
BIN=$MODDIR/system/bin/cameraserver
STAGE=/mnt/rea_cameraserver
LOG=/data/adb/q5q_cam_module.log
log() { echo "$(date '+%m-%d %H:%M:%S') $*" >> $LOG; }

WANT=$(md5sum "$BIN" 2>/dev/null | awk '{print $1}')
RUNNING_BIN=/system/bin/cameraserver

stage_and_mount() {
    mkdir -p "$STAGE" 2>/dev/null
    mountpoint -q "$STAGE" || mount -t tmpfs -o mode=0755 tmpfs "$STAGE" 2>/dev/null
    cp -f "$BIN" "$STAGE/cameraserver" 2>/dev/null || return 1
    chmod 0755 "$STAGE/cameraserver" 2>/dev/null
    chcon u:object_r:cameraserver_exec:s0 "$STAGE/cameraserver" 2>/dev/null
    mount -o bind "$STAGE/cameraserver" "$RUNNING_BIN" 2>/dev/null || return 1
    return 0
}

have=$(md5sum "$RUNNING_BIN" 2>/dev/null | awk '{print $1}')
state=$(getprop init.svc.cameraserver)
log "service(v8): boot state=$state binary=$have want=$WANT"

i=0
while [ $i -lt 12 ]; do
    i=$((i + 1))
    sleep 5
    have=$(md5sum "$RUNNING_BIN" 2>/dev/null | awk '{print $1}')
    state=$(getprop init.svc.cameraserver)
    if [ -n "$WANT" ] && [ "$have" = "$WANT" ] && [ "$state" = "running" ]; then
        log "service(v8): patched cameraserver running (md5 match)"
        exit 0
    fi
done

log "service(v8): still binary=$have state=$state - re-staging from tmpfs"
if stage_and_mount; then
    log "service(v8): re-mounted, restarting cameraserver"
    setprop ctl.restart cameraserver 2>/dev/null
    sleep 8
    log "service(v8): after restart binary=$(md5sum $RUNNING_BIN 2>/dev/null | awk '{print $1}') state=$(getprop init.svc.cameraserver)"
else
    log "service(v8): re-stage FAILED - stock cameraserver stays in use (camera still works, 50MP path inactive)"
fi
