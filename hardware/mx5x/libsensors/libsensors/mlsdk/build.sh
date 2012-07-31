
MAKE_CMD="make \
    VERBOSE=0 \
    TARGET=android \
    DEVICE=MPU3050 \
    CROSS=~/work/myandroid/prebuilt/linux-x86/toolchain/arm-eabi-4.4.3/bin/arm-eabi- \
    ANDROID_ROOT=~/work/myandroid \
    KERNEL_ROOT= ~/repo/mx535/linux \
    PRODUCT=imx53_smd \
"
eval $MAKE_CMD -f Android-shared.mk clean
eval $MAKE_CMD -f Android-shared.mk all
