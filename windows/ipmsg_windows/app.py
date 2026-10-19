"""Simple letter-oriented PySide6 UI, tray receiver and optional user autostart."""
from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

from . import __version__


def set_autostart(enabled: bool) -> None:
    if os.name != "nt": return
    import winreg
    command = f'"{sys.executable}"'
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Microsoft\Windows\CurrentVersion\Run",
                        0, winreg.KEY_SET_VALUE) as key:
        if enabled: winreg.SetValueEx(key, "XiaquiIPMessenger", 0, winreg.REG_SZ, command)
        else:
            try: winreg.DeleteValue(key, "XiaquiIPMessenger")
            except FileNotFoundError: pass


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", action="store_true")
    parser.add_argument("--diagnose", action="store_true")
    args = parser.parse_args()
    if args.version: print(__version__); return 0
    if args.diagnose:
        from .network import lan_broadcasts
        print("IP Messenger Windows", __version__, "UDP/TCP", 42124)
        print("broadcasts", ", ".join(sorted(lan_broadcasts())))
        return 0
    if os.name != "nt":
        print("The GUI is supported on Windows 11 only.", file=sys.stderr); return 2
    from PySide6.QtCore import Qt, QTimer
    from PySide6.QtGui import QAction
    from PySide6.QtWidgets import (QApplication, QCheckBox, QDialog, QHBoxLayout, QLabel,
        QListWidget, QListWidgetItem, QMainWindow, QMessageBox, QPushButton, QSystemTrayIcon,
        QTextEdit, QVBoxLayout)
    from .identity import default_data_dir, load_or_create
    from .network import LanNode
    from .protocol import fingerprint
    from .store import Store

    app = QApplication(sys.argv); app.setQuitOnLastWindowClosed(False)
    data = default_data_dir(); private = load_or_create(data / "identity.dpapi")
    store = Store(data / "messages.sqlite3"); self_id = fingerprint(private.public_key())

    class Window(QMainWindow):
        def __init__(self):
            super().__init__(); self.setWindowTitle("IP Messenger"); self.resize(520, 620)
            root = QVBoxLayout(); root.addWidget(QLabel("IP Messenger — LAN内の手紙"))
            self.peers = QListWidget(); self.peers.setSelectionMode(QListWidget.MultiSelection); root.addWidget(self.peers)
            all_button = QPushButton("📢 全端末（自分を含む）"); all_button.setMinimumHeight(52)
            self_button = QPushButton("📝 自分宛て"); root.addWidget(all_button); root.addWidget(self_button)
            self.body = QTextEdit(); self.body.setPlaceholderText("本文（UTF-8で8192バイトまで）"); root.addWidget(self.body)
            send = QPushButton("選択した端末へ送る"); root.addWidget(send)
            row = QHBoxLayout(); inbox=QPushButton("受信箱"); outbox=QPushButton("送信履歴")
            approve=QPushButton("端末を承認 / 解除"); row.addWidget(inbox);row.addWidget(outbox);row.addWidget(approve);root.addLayout(row)
            auto=QCheckBox("Windowsログイン時に起動（任意）"); auto.toggled.connect(set_autostart);root.addWidget(auto)
            shell=__import__("PySide6.QtWidgets",fromlist=["QWidget"]).QWidget();shell.setLayout(root);self.setCentralWidget(shell)
            all_button.clicked.connect(lambda:self.send_to([p.peer_id for p in store.peers() if p.trusted]+[self_id]))
            self_button.clicked.connect(lambda:self.send_to([self_id]));send.clicked.connect(self.send_selected)
            approve.clicked.connect(self.toggle_trust);inbox.clicked.connect(lambda:self.show_letters(True));outbox.clicked.connect(lambda:self.show_letters(False))
            self.refresh()
        def refresh(self):
            self.peers.clear()
            for peer in store.peers():
                state="ONLINE" if peer.online() else "OFFLINE"; trust="承認済" if peer.trusted else "未承認"
                item=QListWidgetItem(f"{peer.name}  {state} / {trust}\n{peer.peer_id}");item.setData(Qt.UserRole,peer.peer_id);self.peers.addItem(item)
        def send_to(self, ids):
            text=self.body.toPlainText()
            try: store.enqueue(ids,self_id,"Windows",text);self.body.clear();self.refresh()
            except ValueError as e: QMessageBox.warning(self,"送信できません",str(e))
        def send_selected(self): self.send_to([i.data(Qt.UserRole) for i in self.peers.selectedItems()])
        def toggle_trust(self):
            items=self.peers.selectedItems()
            if len(items)!=1: return
            peer=next(p for p in store.peers() if p.peer_id==items[0].data(Qt.UserRole))
            QMessageBox.information(self,"鍵指紋（相手画面と全文照合）",peer.peer_id)
            store.trust(peer.peer_id,not peer.trusted);self.refresh()
        def show_letters(self,incoming):
            dialog=QDialog(self);dialog.setWindowTitle("受信箱" if incoming else "送信履歴");layout=QVBoxLayout(dialog)
            view=QTextEdit();view.setReadOnly(True);view.setPlainText("\n\n".join(f"{x.name} [{x.state}]\n{x.body}" for x in store.letters(incoming)));layout.addWidget(view);dialog.resize(480,500);dialog.exec()
        def closeEvent(self,event): event.ignore();self.hide();tray.showMessage("IP Messenger","受信を続けています")

    window=Window(); tray=QSystemTrayIcon(window);tray.setToolTip("IP Messenger")
    menu=__import__("PySide6.QtWidgets",fromlist=["QMenu"]).QMenu();show=QAction("開く");quit_action=QAction("終了")
    show.triggered.connect(window.show);quit_action.triggered.connect(app.quit);menu.addAction(show);menu.addAction(quit_action);tray.setContextMenu(menu);tray.show()
    node=LanNode(store,private,os.environ.get("COMPUTERNAME","Windows")[:40],lambda:QTimer.singleShot(0,window.refresh),lambda n,b:tray.showMessage(f"{n} から手紙",b[:120]))
    node.start();app.aboutToQuit.connect(node.stop);window.show();return app.exec()


if __name__ == "__main__": raise SystemExit(main())

