#include <linux/module.h>
#include <linux/vermagic.h>
#include <linux/compiler.h>

MODULE_INFO(vermagic, VERMAGIC_STRING);

struct module __this_module
__attribute__((section(".gnu.linkonce.this_module"))) = {
 .name = KBUILD_MODNAME,
 .init = init_module,
#ifdef CONFIG_MODULE_UNLOAD
 .exit = cleanup_module,
#endif
 .arch = MODULE_ARCH_INIT,
};

static const struct modversion_info ____versions[]
__used
__attribute__((section("__versions"))) = {
	{ 0x6ea81300, "module_layout" },
	{ 0xd0972cc5, "skb_queue_head" },
	{ 0x468f0874, "kmalloc_caches" },
	{ 0x12da5bb2, "__kmalloc" },
	{ 0xf9a482f9, "msleep" },
	{ 0xf88c3301, "sg_init_table" },
	{ 0xff178f6, "__aeabi_idivmod" },
	{ 0x2b688622, "complete_and_exit" },
	{ 0xfbc74f64, "__copy_from_user" },
	{ 0x9b48d678, "dev_change_flags" },
	{ 0x63e13d19, "sysfs_remove_bin_file" },
	{ 0x1dd1f7bb, "mem_map" },
	{ 0xd6ee688f, "vmalloc" },
	{ 0x6980fe91, "param_get_int" },
	{ 0x67c2fa54, "__copy_to_user" },
	{ 0x2e5810c6, "__aeabi_unwind_cpp_pr1" },
	{ 0xd8f795ca, "del_timer" },
	{ 0x97255bdf, "strlen" },
	{ 0x382782f5, "wake_lock_destroy" },
	{ 0xbd9d80fe, "dev_set_drvdata" },
	{ 0x5803b156, "hci_register_dev" },
	{ 0xc8b57c27, "autoremove_wake_function" },
	{ 0x4f794e00, "sdio_writesb" },
	{ 0x6763ea7c, "sdio_enable_func" },
	{ 0xc7a4fbed, "rtnl_lock" },
	{ 0x15a0d059, "sdio_claim_irq" },
	{ 0xfa9548d9, "eth_change_mtu" },
	{ 0x9e9a871d, "mmc_wait_for_cmd" },
	{ 0x33ddb1d, "netif_carrier_on" },
	{ 0xf7802486, "__aeabi_uidivmod" },
	{ 0xa358a2ba, "skb_copy" },
	{ 0x43ab66c3, "param_array_get" },
	{ 0xb0bb9c02, "down_interruptible" },
	{ 0xce388354, "netif_carrier_off" },
	{ 0x353e3fa5, "__get_user_4" },
	{ 0x1a41a5ed, "wake_lock" },
	{ 0xfd9378f2, "filp_close" },
	{ 0xeae3dfd6, "__const_udelay" },
	{ 0x74c86cc0, "init_timer_key" },
	{ 0xe61b9131, "hci_recv_frame" },
	{ 0x999e8297, "vfree" },
	{ 0x28118cb6, "__get_user_1" },
	{ 0xff964b25, "param_set_int" },
	{ 0x1e9999f0, "hci_unregister_dev" },
	{ 0x4f099aa5, "wake_unlock" },
	{ 0x3c2c5af5, "sprintf" },
	{ 0x45947727, "param_array_set" },
	{ 0x7d11c268, "jiffies" },
	{ 0x894cd220, "sdio_get_host_pm_caps" },
	{ 0xa45f457, "skb_trim" },
	{ 0xe2d5255a, "strcmp" },
	{ 0x2911eaac, "netif_rx" },
	{ 0xf6288e02, "__init_waitqueue_head" },
	{ 0x583edacf, "mmc_wait_for_req" },
	{ 0xe707d823, "__aeabi_uidiv" },
	{ 0x5baaba0, "wait_for_completion" },
	{ 0xfa2a45e, "__memzero" },
	{ 0x96f21b93, "register_early_suspend" },
	{ 0x5f754e5a, "memset" },
	{ 0x9da810c3, "alloc_etherdev_mq" },
	{ 0x698b9d82, "netif_rx_ni" },
	{ 0x2f433d29, "dev_alloc_skb" },
	{ 0xea147363, "printk" },
	{ 0x42224298, "sscanf" },
	{ 0x71c90087, "memcmp" },
	{ 0xe52592a, "panic" },
	{ 0xaafdc258, "strcasecmp" },
	{ 0x328a05f1, "strncpy" },
	{ 0x4c54d504, "hci_alloc_dev" },
	{ 0x3728f317, "register_netdev" },
	{ 0x8e53acb9, "mmc_set_data_timeout" },
	{ 0x5f1dbaa7, "wireless_send_event" },
	{ 0x84b183ae, "strncmp" },
	{ 0x31654355, "skb_push" },
	{ 0x9a6221c5, "mod_timer" },
	{ 0xf0f1246c, "kvasprintf" },
	{ 0x5ee1321b, "platform_driver_register" },
	{ 0x43b0c9c3, "preempt_schedule" },
	{ 0x415aad97, "skb_pull" },
	{ 0x2196324, "__aeabi_idiv" },
	{ 0xe0a54bc7, "sdio_readsb" },
	{ 0x98df80c4, "sdio_unregister_driver" },
	{ 0xab5ae752, "sdio_set_host_pm_flags" },
	{ 0x9d138776, "skb_queue_tail" },
	{ 0x3ff62317, "local_bh_disable" },
	{ 0x8a4c67cc, "kmem_cache_alloc" },
	{ 0xbc10dd97, "__put_user_4" },
	{ 0xd64f385b, "__alloc_skb" },
	{ 0x93fca811, "__get_free_pages" },
	{ 0x2a71d8bd, "sdio_release_irq" },
	{ 0x108e8985, "param_get_uint" },
	{ 0xe5bca160, "wake_lock_timeout" },
	{ 0xd62c833f, "schedule_timeout" },
	{ 0x1000e51, "schedule" },
	{ 0xada37b39, "kfree_skb" },
	{ 0x2aa0e4fc, "strncasecmp" },
	{ 0x8a7d1c31, "high_memory" },
	{ 0x799aca4, "local_bh_enable" },
	{ 0x3cb58663, "eth_type_trans" },
	{ 0xc27487dd, "__bug" },
	{ 0xa878b1b4, "wake_up_process" },
	{ 0x4101bbde, "param_set_copystring" },
	{ 0x3deb8d16, "ether_setup" },
	{ 0xb9e52429, "__wake_up" },
	{ 0xbc010d75, "sdio_memcpy_toio" },
	{ 0x37a0cba, "kfree" },
	{ 0x5d048f, "kthread_create" },
	{ 0x9d669763, "memcpy" },
	{ 0x89e9d8e5, "wake_lock_init" },
	{ 0x75a17bed, "prepare_to_wait" },
	{ 0x3285cc48, "param_set_uint" },
	{ 0x3743c8ad, "sysfs_create_bin_file" },
	{ 0x8cf51d15, "up" },
	{ 0x8893fa5d, "finish_wait" },
	{ 0x1dcf6794, "unregister_early_suspend" },
	{ 0xcba7b001, "skb_dequeue" },
	{ 0x1becd6e, "unregister_netdev" },
	{ 0xefd6cf06, "__aeabi_unwind_cpp_pr0" },
	{ 0x701d0ebd, "snprintf" },
	{ 0x946f5226, "__netif_schedule" },
	{ 0x49e182c0, "param_get_string" },
	{ 0xe4204a18, "sdio_register_driver" },
	{ 0xbd21880d, "hci_free_dev" },
	{ 0xb7ae2bdc, "consume_skb" },
	{ 0xa7561903, "sdio_memcpy_fromio" },
	{ 0x74de9547, "sdio_claim_host" },
	{ 0xe0068a7b, "platform_driver_unregister" },
	{ 0x85670f1d, "rtnl_is_locked" },
	{ 0x175aa2ea, "skb_put" },
	{ 0x3f6c745c, "dev_get_drvdata" },
	{ 0x30cdf13e, "sdio_set_block_size" },
	{ 0x6e720ff2, "rtnl_unlock" },
	{ 0xd917f5d5, "sdio_disable_func" },
	{ 0x2079df6c, "sdio_release_host" },
	{ 0xe914e41e, "strcpy" },
	{ 0x4e9f920e, "filp_open" },
};

static const char __module_depends[]
__used
__attribute__((section(".modinfo"))) =
"depends=";

MODULE_ALIAS("sdio:c*v0271d0300*");
MODULE_ALIAS("sdio:c*v0271d0301*");
