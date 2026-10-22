"""Simple letter-oriented PySide6 UI, tray receiver and optional user autostart."""
from __future__ import annotations

import argparse
import os
import sys
import threading

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
    from PySide6.QtCore import QObject, QSettings, Qt, Signal
    from PySide6.QtNetwork import QLocalServer, QLocalSocket
    from PySide6.QtGui import QAction
    from PySide6.QtWidgets import (QApplication, QCheckBox, QDialog, QFileDialog, QHBoxLayout, QLabel,
        QInputDialog, QLineEdit, QListWidget, QListWidgetItem, QMainWindow, QMessageBox, QPushButton, QStyle,
        QSystemTrayIcon, QTextEdit, QVBoxLayout)
    from .identity import default_data_dir, load_or_create
    from .network import LanNode
    from .protocol import fingerprint
    from .store import Store

    app = QApplication.instance() or QApplication(sys.argv); app.setQuitOnLastWindowClosed(False)
    instance_name = "Xiaqui-IPMessenger-v1"
    probe = QLocalSocket(); probe.connectToServer(instance_name)
    if probe.waitForConnected(250):
        probe.write(b"show"); probe.waitForBytesWritten(250); return 0
    instance_server = QLocalServer(app)
    if not instance_server.listen(instance_name):
        QMessageBox.critical(None,"IP Messenger","別のインスタンスが動作中か、二重起動防止用のローカル受口を作成できません")
        return 3
    data = default_data_dir(); private = load_or_create(data / "identity.dpapi")
    store = Store(data / "messages.sqlite3"); self_id = fingerprint(private.public_key())
    settings = QSettings("Xiaqui", "IPMessenger")

    class Bridge(QObject):
        changed = Signal()
        arrived = Signal(str, str)
        fileOffer = Signal(str, str)
        completed = Signal(object)

    bridge = Bridge()
    bridge.completed.connect(lambda callback:callback())

    class Window(QMainWindow):
        def __init__(self):
            super().__init__(); self.setWindowTitle("IP Messenger"); self.resize(520, 620)
            root = QVBoxLayout(); root.addWidget(QLabel("IP Messenger — LAN内の手紙"))
            root.addWidget(QLabel(f"この端末の鍵指紋（64桁）: {self_id}"))
            self.peers = QListWidget(); self.peers.setSelectionMode(QListWidget.MultiSelection); root.addWidget(self.peers)
            self.recipients = None  # None: selected peers, otherwise explicit destination IDs
            self.destination = QLabel("宛先: 選択した端末"); root.addWidget(self.destination)
            self.peers.itemSelectionChanged.connect(self.use_selected_peers)
            all_button = QPushButton("📢 全端末（自分を含む）"); all_button.setMinimumHeight(52)
            self_button = QPushButton("📝 自分宛て"); root.addWidget(all_button); root.addWidget(self_button)
            self.body = QTextEdit(); self.body.setPlaceholderText("本文（UTF-8で8192バイトまで）"); root.addWidget(self.body)
            send = QPushButton("選択した端末へ送る"); root.addWidget(send)
            send_files = QPushButton("ファイルを選んで送る");root.addWidget(send_files)
            row = QHBoxLayout(); inbox=QPushButton("受信箱"); outbox=QPushButton("送信履歴")
            approve=QPushButton("端末を承認 / 解除"); files=QPushButton("ファイル転送")
            row.addWidget(inbox);row.addWidget(outbox);row.addWidget(files);row.addWidget(approve);root.addLayout(row)
            self.device_name=QLineEdit(str(settings.value("deviceName",os.environ.get("COMPUTERNAME","Windows")))[:40])
            self.device_name.setPlaceholderText("この端末の名前");root.addWidget(self.device_name)
            save_name=QPushButton("端末名を保存");save_name.clicked.connect(self.save_name);root.addWidget(save_name)
            auto=QCheckBox("Windowsログイン時に起動（任意）"); auto.toggled.connect(set_autostart);root.addWidget(auto)
            shell=__import__("PySide6.QtWidgets",fromlist=["QWidget"]).QWidget();shell.setLayout(root);self.setCentralWidget(shell)
            all_button.clicked.connect(lambda:self.select_destination([p.peer_id for p in store.peers() if p.trusted]+[self_id], "全端末（自分を含む）"))
            self_button.clicked.connect(lambda:self.select_destination([self_id], "自分宛て"));send.clicked.connect(self.send_selected)
            send_files.clicked.connect(self.pick_files);files.clicked.connect(self.show_transfers)
            approve.clicked.connect(self.toggle_trust);inbox.clicked.connect(lambda:self.show_letters(True));outbox.clicked.connect(lambda:self.show_letters(False))
            self.refresh()
        def refresh(self):
            # Rebuilding the list is not a user change of destination.
            selected_ids = {i.data(Qt.UserRole) for i in self.peers.selectedItems()}
            self.peers.blockSignals(True)
            try:
                self.peers.clear()
                own_name=str(settings.value("deviceName",os.environ.get("COMPUTERNAME","Windows")))[:40]
                own=QListWidgetItem(f"{own_name}（自分） ONLINE")
                own.setData(Qt.UserRole,self_id);self.peers.addItem(own)
                own.setSelected(self_id in selected_ids)
                for peer in store.peers():
                    state="ONLINE" if peer.online() else "OFFLINE"; trust="承認済" if peer.trusted else "未承認"
                    name="".join(ch if ch.isprintable() and ch not in "\r\n" else "�" for ch in peer.name)
                    label = f"{name}  {state} / {trust}  [ID:{peer.peer_id[:8]}]" if peer.trusted else f"{name}  {state} / {trust}\n{peer.peer_id}"
                    item=QListWidgetItem(label);item.setData(Qt.UserRole,peer.peer_id)
                    self.peers.addItem(item)
                    if peer.peer_id in selected_ids: item.setSelected(True)
            finally:
                self.peers.blockSignals(False)
        def use_selected_peers(self):
            self.recipients = None
            self.destination.setText("宛先: 選択した端末")
        def select_destination(self, ids, title):
            # Display selected recipients in the same list used for manual selection.
            # Self is not a peer row, so keep explicit IDs when it is included.
            selected_ids = set(ids)
            self.peers.blockSignals(True)
            try:
                for i in range(self.peers.count()):
                    item = self.peers.item(i)
                    item.setSelected(item.data(Qt.UserRole) in selected_ids)
            finally:
                self.peers.blockSignals(False)
            self.recipients = None
            self.destination.setText(f"宛先: {title}")
        def send_to(self, ids):
            text=self.body.toPlainText()
            try: store.enqueue(ids,self_id,node.name,text);self.body.clear();self.refresh()
            except ValueError as e: QMessageBox.warning(self,"送信できません",str(e))
        def send_selected(self):
            ids = self.recipients if self.recipients is not None else [i.data(Qt.UserRole) for i in self.peers.selectedItems()]
            self.send_to(ids)
        def pick_files(self):
            ids=[i.data(Qt.UserRole) for i in self.peers.selectedItems()]
            remote=[ident for ident in ids if ident!=self_id]
            if not ids:
                QMessageBox.warning(self,"ファイル送信","宛先を選んでください");return
            unsupported=[p.name for p in store.peers() if p.peer_id in remote and not p.file_capable]
            if unsupported:
                QMessageBox.warning(self,"ファイル送信","ファイル転送非対応: "+"、".join(unsupported));return
            paths,_=QFileDialog.getOpenFileNames(self,"送るファイルを選択")
            if not paths:return
            def prepare():
                try:
                    from .file_transfer import queue_paths
                    queue_paths(store,ids,paths,self_id,node.name)
                    bridge.completed.emit(lambda:(self.refresh(),QMessageBox.information(self,"ファイル送信","転送を開始しました")))
                except Exception as error:
                    bridge.completed.emit(lambda e=str(error):QMessageBox.warning(self,"ファイル送信",e))
            threading.Thread(target=prepare,daemon=True).start()
        def toggle_trust(self):
            items=self.peers.selectedItems()
            if len(items)!=1: return
            if items[0].data(Qt.UserRole)==self_id: return
            peer=next(p for p in store.peers() if p.peer_id==items[0].data(Qt.UserRole))
            if peer.trusted:
                answer=QMessageBox.question(self,"承認解除",f"{peer.name} の承認を解除しますか？")
            else:
                answer=QMessageBox.question(self,"双方で鍵指紋を全文照合",
                    f"相手画面と64桁すべてが一致した場合だけ承認してください。\n\n{peer.peer_id}")
            if answer==QMessageBox.Yes:
                store.trust(peer.peer_id, not peer.trusted)
                # Preset recipients must be recalculated after trust changes.
                if self.recipients is not None:
                    trusted_ids = {p.peer_id for p in store.peers() if p.trusted}
                    self.recipients = [ident for ident in self.recipients if ident == self_id or ident in trusted_ids]
                self.refresh()
        def save_name(self):
            value=self.device_name.text().strip()[:40]
            if not value: QMessageBox.warning(self,"端末名","空の端末名は使えません");return
            settings.setValue("deviceName",value);node.name=value;self.refresh()
        def show_letters(self,incoming):
            dialog=QDialog(self);dialog.setWindowTitle("受信箱" if incoming else "送信履歴");layout=QVBoxLayout(dialog)
            listing=QListWidget();layout.addWidget(listing)
            view=QTextEdit();view.setReadOnly(True);layout.addWidget(view)
            letters=[]
            def reload():
                letters[:]=store.letters(incoming);listing.clear();view.clear()
                for letter in letters: listing.addItem(f"{letter.name} [{letter.state}] {letter.body[:45]}")
            def current():
                row=listing.currentRow()
                return letters[row] if 0<=row<len(letters) else None
            listing.currentRowChanged.connect(lambda _row:view.setPlainText(current().body if current() else ""))
            controls=QHBoxLayout();copy=QPushButton("本文をコピー");delete=QPushButton("削除")
            controls.addWidget(copy);controls.addWidget(delete);layout.addLayout(controls)
            copy.clicked.connect(lambda:QApplication.clipboard().setText(current().body) if current() else None)
            def delete_current():
                letter=current()
                if not letter:return
                if QMessageBox.question(dialog,"この端末の記録を1件削除",
                    "相手端末の履歴は削除されません。保留の再送を停止します。既に送信された本文は取り消せません。")!=QMessageBox.Yes:return
                delete.setEnabled(False)
                def remove():
                    try:
                        store.delete_letter(letter)
                        bridge.completed.emit(lambda:(reload(),delete.setEnabled(True)))
                    except Exception:
                        bridge.completed.emit(lambda:(delete.setEnabled(True),QMessageBox.warning(self,"削除","削除できませんでした")))
                threading.Thread(target=remove,daemon=True).start()
            delete.clicked.connect(delete_current);reload();dialog.resize(480,500);dialog.exec()
        def show_transfers(self):
            dialog=QDialog(self);dialog.setWindowTitle("ファイル転送");layout=QVBoxLayout(dialog)
            listing=QListWidget();layout.addWidget(listing);details=QTextEdit();details.setReadOnly(True);layout.addWidget(details)
            transfers=[]
            def reload():
                transfers[:]=store.transfers();listing.clear()
                for transfer in transfers:
                    direction="受信" if transfer.incoming else "送信"
                    listing.addItem(f"{direction} {transfer.peer_name} [{transfer.state}] "+
                                    ", ".join(f.name for f in store.transfer_files(transfer.transfer_id)))
            def current():
                row=listing.currentRow();return transfers[row] if 0<=row<len(transfers) else None
            def show_detail(_row):
                transfer=current()
                if not transfer:details.clear();return
                lines=[]
                for file in store.transfer_files(transfer.transfer_id):
                    percent=100 if file.size==0 and file.state=="COMPLETED" else (file.offset*100//max(1,file.size))
                    lines.append(f"{file.name}\n{file.offset}/{file.size} bytes ({percent}%) {file.state}")
                details.setPlainText("\n\n".join(lines)+(f"\n\nエラー: {transfer.error}" if transfer.error else ""))
            listing.currentRowChanged.connect(show_detail)
            controls=QHBoxLayout();accept=QPushButton("受信して保存先を選択");decline=QPushButton("拒否 / 取消")
            open_file=QPushButton("完了ファイルを開く")
            controls.addWidget(accept);controls.addWidget(decline);controls.addWidget(open_file);layout.addLayout(controls)
            def accept_current():
                transfer=current()
                if not transfer or not transfer.incoming or transfer.state not in ("WAITING_ACCEPT","PAUSED"):return
                destination=QFileDialog.getExistingDirectory(dialog,"保存先を選択")
                if not destination:return
                accept.setEnabled(False)
                def prepare():
                    try:
                        from .file_transfer import accept_to_directory
                        accept_to_directory(store,transfer,destination)
                        bridge.completed.emit(lambda:(reload(),self.refresh(),accept.setEnabled(True)))
                    except Exception as error:
                        bridge.completed.emit(lambda e=str(error):(accept.setEnabled(True),QMessageBox.warning(dialog,"受信できません",e)))
                threading.Thread(target=prepare,daemon=True).start()
            def decline_current():
                transfer=current()
                if not transfer or transfer.state in ("COMPLETED","CANCELLED","DECLINED"):return
                if QMessageBox.question(dialog,"転送を中止","この転送を取り消しますか？")!=QMessageBox.Yes:return
                store.set_transfer_state(transfer.transfer_id,transfer.peer_id,transfer.incoming,
                                         "DECLINED" if transfer.incoming else "CANCEL_PENDING","CANCELLED",0)
                reload();self.refresh()
            accept.clicked.connect(accept_current);decline.clicked.connect(decline_current)
            def open_current():
                transfer=current()
                if not transfer or not transfer.incoming:return
                available=[f for f in store.transfer_files(transfer.transfer_id) if f.state=="COMPLETED" and f.final]
                if not available:return
                names=[f.name for f in available]
                chosen,ok=QInputDialog.getItem(dialog,"完了ファイルを開く","ファイル",names,0,False)
                if ok:
                    target=next(f.final for f in available if f.name==chosen)
                    try:os.startfile(target)
                    except OSError as error:QMessageBox.warning(dialog,"開けません",str(error))
            open_file.clicked.connect(open_current)
            reload();dialog.resize(620,520);dialog.exec()
        def closeEvent(self,event): event.ignore();self.hide();tray.showMessage("IP Messenger","受信を続けています")

    window=Window(); tray=QSystemTrayIcon(window);tray.setToolTip("IP Messenger")
    tray.setIcon(window.style().standardIcon(QStyle.SP_MessageBoxInformation))
    menu=__import__("PySide6.QtWidgets",fromlist=["QMenu"]).QMenu();show=QAction("開く");quit_action=QAction("終了")
    def reveal_window():
        window.showNormal(); window.raise_(); window.activateWindow()
    show.triggered.connect(reveal_window);quit_action.triggered.connect(app.quit);menu.addAction(show);menu.addAction(quit_action);tray.setContextMenu(menu);tray.show()
    tray.activated.connect(lambda reason: reveal_window() if reason == QSystemTrayIcon.Trigger else None)
    bridge.changed.connect(window.refresh)
    bridge.arrived.connect(lambda n,b:tray.showMessage(f"{n} から手紙",b[:120]))
    bridge.fileOffer.connect(lambda n,_t:tray.showMessage(f"{n} からファイル", "アプリを開いて保存先を選んでください"))
    node=LanNode(store,private,str(settings.value("deviceName",os.environ.get("COMPUTERNAME","Windows")))[:40],
                 bridge.changed.emit,bridge.arrived.emit,bridge.fileOffer.emit)
    def activate_existing():
        connection=instance_server.nextPendingConnection()
        if connection: connection.waitForReadyRead(100); connection.deleteLater()
        reveal_window()
    instance_server.newConnection.connect(activate_existing)
    try: node.start()
    except OSError as error:
        QMessageBox.critical(window,"LAN受信を開始できません",f"UDP/TCP 42124を確保できません。\n{error}")
        node.stop();return 4
    app.aboutToQuit.connect(node.stop);window.show();return app.exec()


if __name__ == "__main__": raise SystemExit(main())


