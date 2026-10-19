a = Analysis(["launcher.py"], pathex=["."], binaries=[], datas=[],
             hiddenimports=[], excludes=["tkinter", "PySide6.Qt3DCore", "PySide6.Qt3DRender",
             "PySide6.QtWebEngineCore", "PySide6.QtWebEngineWidgets"], noarchive=False)
pyz = PYZ(a.pure)
exe = EXE(pyz, a.scripts, a.binaries, a.datas, [], name="ipmsg-windows",
          debug=False, bootloader_ignore_signals=False, strip=False, upx=False,
          console=False, disable_windowed_traceback=False)


