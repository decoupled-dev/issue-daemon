# Include from your device/product makefile:
#     $(call inherit-product, vendor/<oem>/apps/IssueDaemon/aosp/issue_daemon.mk)
PRODUCT_PACKAGES += IssueDaemon

# Optional fleet provisioning without rebuilding the config file:
# PRODUCT_SYSTEM_EXT_PROPERTIES += persist.sys.issuedaemon.server_url=https://ecu.example.com
