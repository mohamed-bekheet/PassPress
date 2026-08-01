from __future__ import annotations

from dataclasses import dataclass
import json
from math import cos, radians, sin
from pathlib import Path

from PySide6.QtCore import QPointF, QRectF, Qt, QTimer, Signal
from PySide6.QtGui import QColor, QDoubleValidator, QImageReader, QPainter, QPen, QPixmap
from PySide6.QtWidgets import (
    QApplication,
    QCheckBox,
    QDialog,
    QDialogButtonBox,
    QFileDialog,
    QFrame,
    QGridLayout,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QMainWindow,
    QMessageBox,
    QPushButton,
    QProgressDialog,
    QSlider,
    QStatusBar,
    QVBoxLayout,
    QWidget,
)

from .hid_transport import HidTransport
from .models import DeviceMode, DeviceStatus, EMPTY_STATUS


MODE_LABELS = {
    DeviceMode.NON_TRUSTED: "Non-Trusted",
    DeviceMode.TRUSTED: "Trusted",
    DeviceMode.ADMIN: "Admin",
}

MODE_COLORS = {
    DeviceMode.NON_TRUSTED: "#64748B",
    DeviceMode.TRUSTED: "#10B981",
    DeviceMode.ADMIN: "#F97316",
}

# Visual order (top-left, top-mid, top-right, bottom-left, bottom-mid, bottom-right)
# to logical button index (0-based). Requested mapping:
# TL=2, TM=4, TR=6, BL=1, BM=3, BR=5
VISUAL_TO_LOGICAL_INDEX = [1, 3, 5, 0, 2, 4]
LOGICAL_TO_VISUAL_INDEX = [3, 0, 4, 1, 5, 2]

APP_STYLE = """
QMainWindow {
    background-color: #0b1220;
}

QWidget {
    color: #e5e7eb;
    font-size: 13px;
}

#SidePanel {
    background-color: #111827;
    border: 1px solid #1f2937;
    border-radius: 14px;
}

#CalibrationPanel {
    background-color: #0b1220;
    border: 1px solid #1f2937;
    border-radius: 12px;
}

#SectionTitle {
    font-size: 17px;
    font-weight: 650;
    color: #f3f4f6;
}

#ModeBadge {
    background-color: #1f2937;
    border: 1px solid #374151;
    border-radius: 10px;
    padding: 7px 10px;
    font-weight: 600;
}

#SelectedButtonBadge {
    background-color: #0b1220;
    border: 1px solid #334155;
    border-radius: 12px;
    padding: 9px 12px;
    font-size: 14px;
    font-weight: 650;
    color: #e2e8f0;
}

#StatusReport {
    background-color: #0b1220;
    border: 1px solid #1f2937;
    border-radius: 10px;
    padding: 8px 10px;
    color: #cbd5e1;
    font-family: Consolas, "Courier New", monospace;
}

#CoordLabel {
    background-color: #0b1220;
    border: 1px solid #1f2937;
    border-radius: 8px;
    padding: 6px 8px;
    color: #9ca3af;
    font-family: Consolas, "Courier New", monospace;
}

QLineEdit {
    background-color: #0b1220;
    border: 1px solid #334155;
    border-radius: 8px;
    padding: 8px 10px;
    selection-background-color: #2563eb;
}

QLineEdit:focus {
    border: 1px solid #3b82f6;
}

QCheckBox {
    spacing: 8px;
}

QCheckBox::indicator {
    width: 16px;
    height: 16px;
    border-radius: 4px;
    border: 1px solid #475569;
    background: #0b1220;
}

QCheckBox::indicator:checked {
    background: #22c55e;
    border: 1px solid #22c55e;
}

QPushButton {
    border-radius: 10px;
    padding: 9px 14px;
    border: 1px solid #374151;
    background-color: #1f2937;
    font-weight: 600;
    font-size: 12.5px;
}

QPushButton:hover {
    background-color: #273549;
}

QPushButton:pressed {
    background-color: #1b2738;
}

#PrimaryButton {
    background-color: #1d4ed8;
    border: 1px solid #2563eb;
}

#PrimaryButton:hover {
    background-color: #2563eb;
}

#SuccessButton {
    background-color: #166534;
    border: 1px solid #16a34a;
}

#SuccessButton:hover {
    background-color: #15803d;
}

QSlider::groove:horizontal {
    border: 1px solid #334155;
    height: 6px;
    border-radius: 3px;
    background: #0b1220;
}

QSlider::handle:horizontal {
    background: #60a5fa;
    border: 1px solid #93c5fd;
    width: 16px;
    margin: -6px 0;
    border-radius: 8px;
}

QStatusBar {
    background-color: #111827;
    border-top: 1px solid #1f2937;
}
"""


@dataclass(slots=True)
class Hotspot:
    center_x: float
    center_y: float
    diameter: float


class BoardMapWidget(QWidget):
    button_clicked = Signal(int)
    calibration_clicked = Signal(float, float, int)

    def __init__(self, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self.setMouseTracking(True)
        self._hover_index = -1
        self._selected_index = 0
        self._flash_index = -1
        self._calibration_enabled = False
        self._rotation_deg = 0
        self._pixmap = QPixmap()
        # Tuned for provided board image (approx. 1600x760).
        # Parameters are normalized [0..1]: center_x, center_y, diameter.
        diameter = 0.21
        centers = [
            (0.177, 0.259),
            (0.404, 0.259),
            (0.632, 0.259),
            (0.174, 0.729),
            (0.401, 0.729),
            (0.628, 0.729),
        ]
        self._hotspots = [
            Hotspot(center_x=x, center_y=y, diameter=diameter)
            for x, y in centers
        ]

    def set_board_image(self, file_path: str) -> None:
        reader = QImageReader(file_path)
        reader.setAutoTransform(True)
        image = reader.read()
        if not image.isNull():
            self._pixmap = QPixmap.fromImage(image)
        self.update()

    def set_calibration_enabled(self, enabled: bool) -> None:
        self._calibration_enabled = enabled
        self.update()

    def set_rotation_degrees(self, degrees: int) -> None:
        self._rotation_deg = degrees
        self.update()

    def set_flashed_button(self, index: int) -> None:
        self._flash_index = index
        self.update()

    def set_selected_button(self, index: int) -> None:
        if 0 <= index < len(self._hotspots):
            self._selected_index = index
        else:
            self._selected_index = -1
        self.update()

    def get_circle_params(self) -> tuple[float, list[tuple[float, float]]]:
        if not self._hotspots:
            return 0.0, []
        diameter = self._hotspots[0].diameter
        centers = [(item.center_x, item.center_y) for item in self._hotspots]
        return diameter, centers

    def set_circle_params(self, diameter: float, centers: list[tuple[float, float]]) -> None:
        if diameter <= 0.0 or not centers or len(centers) != len(self._hotspots):
            return

        normalized_d = max(0.01, min(0.95, diameter))
        updated: list[Hotspot] = []
        for center_x, center_y in centers:
            clamped_x = max(0.0, min(1.0, center_x))
            clamped_y = max(0.0, min(1.0, center_y))
            updated.append(Hotspot(center_x=clamped_x, center_y=clamped_y, diameter=normalized_d))

        self._hotspots = updated
        self.update()

    def mouseMoveEvent(self, event) -> None:
        index, _, _ = self._index_at(event.position())
        if index != self._hover_index:
            self._hover_index = index
            self.update()

    def leaveEvent(self, event) -> None:
        self._hover_index = -1
        self.update()

    def mousePressEvent(self, event) -> None:
        if event.button() != Qt.LeftButton:
            return
        index, norm_x, norm_y = self._index_at(event.position())

        if self._calibration_enabled:
            self.calibration_clicked.emit(norm_x, norm_y, index)

        if index >= 0:
            self._selected_index = index
            self.button_clicked.emit(index)
            self.update()

    def _index_at(self, point: QPointF) -> tuple[int, float, float]:
        draw_rect = self._draw_rect()
        if draw_rect.width() <= 0 or draw_rect.height() <= 0:
            return -1, -1.0, -1.0

        unrotated = self._rotate_point(point, draw_rect.center(), -self._rotation_deg)
        norm_x = (unrotated.x() - draw_rect.left()) / draw_rect.width()
        norm_y = (unrotated.y() - draw_rect.top()) / draw_rect.height()

        for idx, item in enumerate(self._hotspots):
            rect = self._circle_to_rect(item, draw_rect)
            if rect.contains(unrotated):
                return idx, norm_x, norm_y

        return -1, norm_x, norm_y

    def _board_rect(self) -> QRectF:
        margin = 8.0
        return QRectF(margin, margin, self.width() - 2 * margin, self.height() - 2 * margin)

    def _circle_to_rect(self, circle: Hotspot, board_rect: QRectF) -> QRectF:
        diameter_w = circle.diameter * board_rect.width()
        diameter_h = circle.diameter * board_rect.width()
        center_x = board_rect.left() + (circle.center_x * board_rect.width())
        center_y = board_rect.top() + (circle.center_y * board_rect.height())
        return QRectF(
            center_x - (diameter_w / 2.0),
            center_y - (diameter_h / 2.0),
            diameter_w,
            diameter_h,
        )

    def _draw_rect(self) -> QRectF:
        board_rect = self._board_rect()
        if self._pixmap.isNull():
            return board_rect

        pixmap_w = float(self._pixmap.width())
        pixmap_h = float(self._pixmap.height())
        if pixmap_w <= 0.0 or pixmap_h <= 0.0:
            return board_rect

        scale = min(board_rect.width() / pixmap_w, board_rect.height() / pixmap_h)
        scaled_w = pixmap_w * scale
        scaled_h = pixmap_h * scale

        return QRectF(
            board_rect.center().x() - scaled_w / 2.0,
            board_rect.center().y() - scaled_h / 2.0,
            scaled_w,
            scaled_h,
        )

    def _rotate_point(self, point: QPointF, center: QPointF, degrees: float) -> QPointF:
        angle = radians(degrees)
        dx = point.x() - center.x()
        dy = point.y() - center.y()
        rx = (dx * cos(angle)) - (dy * sin(angle))
        ry = (dx * sin(angle)) + (dy * cos(angle))
        return QPointF(center.x() + rx, center.y() + ry)

    def paintEvent(self, event) -> None:
        painter = QPainter(self)
        painter.setRenderHint(QPainter.Antialiasing)
        painter.setRenderHint(QPainter.SmoothPixmapTransform, True)

        board_rect = self._draw_rect()
        painter.fillRect(self.rect(), QColor("#0f172a"))

        if self._pixmap.isNull():
            painter.setBrush(QColor("#214d2f"))
            painter.setPen(Qt.NoPen)
            painter.drawRoundedRect(board_rect, 20, 20)
            painter.setPen(QPen(QColor("#d1d5db"), 1))
            painter.drawText(board_rect, Qt.AlignCenter, "Board image placeholder")
        else:
            painter.save()
            painter.translate(board_rect.center())
            painter.rotate(self._rotation_deg)
            painter.translate(-board_rect.center())
            painter.drawPixmap(board_rect, self._pixmap, QRectF(self._pixmap.rect()))
            painter.restore()

        painter.save()
        painter.translate(board_rect.center())
        painter.rotate(self._rotation_deg)
        painter.translate(-board_rect.center())

        for idx, item in enumerate(self._hotspots):
            rect = self._circle_to_rect(item, board_rect)
            is_selected = idx == self._selected_index
            is_hover = idx == self._hover_index

            if is_selected and is_hover:
                painter.setPen(QPen(QColor("#38bdf8"), 4))
                painter.setBrush(QColor(56, 189, 248, 70))
                painter.drawEllipse(rect)
            elif is_selected:
                painter.setPen(QPen(QColor("#0ea5e9"), 3))
                painter.setBrush(QColor(14, 165, 233, 55))
                painter.drawEllipse(rect)
            elif is_hover:
                painter.setPen(QPen(QColor("#60a5fa"), 3))
                painter.setBrush(QColor(96, 165, 250, 40))
                painter.drawEllipse(rect)
            elif self._calibration_enabled:
                painter.setPen(QPen(QColor("#f59e0b"), 2, Qt.DashLine))
                painter.setBrush(QColor(245, 158, 11, 25))
                painter.drawEllipse(rect)
        painter.restore()


class AdminDialog(QDialog):
    def __init__(self, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self.setWindowTitle("Enter Admin Password")
        layout = QVBoxLayout(self)

        self.password_input = QLineEdit(self)
        self.password_input.setEchoMode(QLineEdit.Password)
        self.password_input.setPlaceholderText("Admin password")
        layout.addWidget(self.password_input)

        buttons = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)
        layout.addWidget(buttons)

    @property
    def password(self) -> str:
        return self.password_input.text()


class MainWindow(QMainWindow):
    def __init__(self) -> None:
        super().__init__()
        self.setWindowTitle("PASSPRESS HID Control Center")
        self.resize(700, 420)
        self._apply_modern_theme()

        self._transport = HidTransport(self)
        self._status = EMPTY_STATUS
        self._selected_button = 0

        self._build_ui()
        self._load_default_board_image()
        self._load_calibration_fields_from_hotspots()
        self._bind_signals()

        self._transport.start()

    def _apply_modern_theme(self) -> None:
        self.setStyleSheet(APP_STYLE)

    def _load_default_board_image(self) -> None:
        candidate_paths = [
            Path(__file__).resolve().parents[1] / "assets" / "board.png",
            Path(__file__).resolve().parents[1] / "assets" / "board.jpg",
            Path(__file__).resolve().parents[1] / "assets" / "board.jpeg",
        ]

        for path in candidate_paths:
            if path.exists():
                self.board_widget.set_board_image(str(path))
                break

    def _build_ui(self) -> None:
        root = QWidget(self)
        root_layout = QHBoxLayout(root)
        root_layout.setContentsMargins(12, 12, 12, 12)
        root_layout.setSpacing(12)

        self.board_widget = BoardMapWidget(root)
        self.board_widget.setMinimumSize(700, 420)
        root_layout.addWidget(self.board_widget, 3)

        panel = QFrame(root)
        panel.setObjectName("SidePanel")
        panel.setFrameShape(QFrame.StyledPanel)
        panel_layout = QVBoxLayout(panel)
        panel_layout.setSpacing(10)

        title = QLabel("Button Settings", panel)
        title.setObjectName("SectionTitle")
        panel_layout.addWidget(title)

        self.mode_badge = QLabel("Mode: Non-Trusted", panel)
        self.mode_badge.setObjectName("ModeBadge")
        panel_layout.addWidget(self.mode_badge)

        status_title = QLabel("Status Report", panel)
        status_title.setObjectName("SectionTitle")
        panel_layout.addWidget(status_title)

        self.status_report_label = QLabel(
            "Connection: --\nReports: 0\nUptime: 00:00\nLast Active Btn: --\nLast Event: --",
            panel,
        )
        self.status_report_label.setObjectName("StatusReport")
        self.status_report_label.setWordWrap(True)
        panel_layout.addWidget(self.status_report_label)

        self.calibration_toggle_button = QPushButton("Open Calibration", panel)
        panel_layout.addWidget(self.calibration_toggle_button)

        self.calibration_panel = QFrame(panel)
        self.calibration_panel.setObjectName("CalibrationPanel")
        self.calibration_panel.setFrameShape(QFrame.StyledPanel)
        calibration_layout = QVBoxLayout(self.calibration_panel)
        calibration_layout.setContentsMargins(8, 8, 8, 8)
        calibration_layout.setSpacing(8)

        calibration_title = QLabel("Calibration", self.calibration_panel)
        calibration_title.setObjectName("SectionTitle")
        calibration_layout.addWidget(calibration_title)

        self.rotation_label = QLabel("Rotation: 0°", self.calibration_panel)
        calibration_layout.addWidget(self.rotation_label)

        self.rotation_slider = QSlider(Qt.Horizontal, self.calibration_panel)
        self.rotation_slider.setRange(-180, 180)
        self.rotation_slider.setSingleStep(1)
        self.rotation_slider.setValue(0)
        calibration_layout.addWidget(self.rotation_slider)

        self.diameter_label = QLabel("Circle diameter (%)", self.calibration_panel)
        calibration_layout.addWidget(self.diameter_label)

        self.diameter_input = QLineEdit(self.calibration_panel)
        self.diameter_input.setPlaceholderText("e.g. 19.1")
        self.diameter_input.setValidator(QDoubleValidator(0.01, 95.0, 3, self))
        calibration_layout.addWidget(self.diameter_input)

        centers_title = QLabel("Centers (%) — X / Y", self.calibration_panel)
        calibration_layout.addWidget(centers_title)

        self.center_x_inputs: list[QLineEdit] = []
        self.center_y_inputs: list[QLineEdit] = []
        centers_grid = QGridLayout()
        centers_grid.addWidget(QLabel("Btn", self.calibration_panel), 0, 0)
        centers_grid.addWidget(QLabel("Center X", self.calibration_panel), 0, 1)
        centers_grid.addWidget(QLabel("Center Y", self.calibration_panel), 0, 2)

        for idx in range(6):
            btn_number = VISUAL_TO_LOGICAL_INDEX[idx] + 1
            btn_label = QLabel(f"{btn_number}", self.calibration_panel)
            x_input = QLineEdit(self.calibration_panel)
            y_input = QLineEdit(self.calibration_panel)
            x_input.setValidator(QDoubleValidator(0.0, 100.0, 3, self))
            y_input.setValidator(QDoubleValidator(0.0, 100.0, 3, self))
            x_input.setPlaceholderText("X %")
            y_input.setPlaceholderText("Y %")
            self.center_x_inputs.append(x_input)
            self.center_y_inputs.append(y_input)
            centers_grid.addWidget(btn_label, idx + 1, 0)
            centers_grid.addWidget(x_input, idx + 1, 1)
            centers_grid.addWidget(y_input, idx + 1, 2)

        calibration_layout.addLayout(centers_grid)

        calibration_actions = QGridLayout()
        self.apply_calibration_button = QPushButton("Apply Circle Calibration", self.calibration_panel)
        self.export_calibration_button = QPushButton("Export Calibration", self.calibration_panel)
        self.import_calibration_button = QPushButton("Import Calibration", self.calibration_panel)
        calibration_actions.addWidget(self.apply_calibration_button, 0, 0, 1, 2)
        calibration_actions.addWidget(self.export_calibration_button, 1, 0)
        calibration_actions.addWidget(self.import_calibration_button, 1, 1)
        calibration_layout.addLayout(calibration_actions)

        self.coords_label = QLabel("x=--.--, y=--.--, btn=--", self.calibration_panel)
        self.coords_label.setObjectName("CoordLabel")
        calibration_layout.addWidget(self.coords_label)

        self.calibration_panel.setVisible(False)
        panel_layout.addWidget(self.calibration_panel)

        self.button_label = QLabel("Selected • Button 1", panel)
        self.button_label.setObjectName("SelectedButtonBadge")
        panel_layout.addWidget(self.button_label)

        self.password_edit = QLineEdit(panel)
        self.password_edit.setEchoMode(QLineEdit.Password)
        self.password_edit.setStyleSheet("font-family: Consolas, 'Courier New', monospace;")
        panel_layout.addWidget(self.password_edit)

        self.append_enter_check = QCheckBox("Append Enter", panel)
        panel_layout.addWidget(self.append_enter_check)

        actions = QGridLayout()
        self.lock_button = QPushButton("Admin Access", panel)
        self.lock_button.setObjectName("PrimaryButton")
        self.nontrusted_button = QPushButton("Switch to Non-Trusted", panel)
        self.nontrusted_button.setObjectName("SecondaryButton")
        self.save_button = QPushButton("Apply Changes", panel)
        self.save_button.setObjectName("SuccessButton")
        self.firmware_update_button = QPushButton("Firmware Update", panel)
        self.firmware_update_button.setObjectName("PrimaryButton")
        self.app_addr_edit = QLineEdit(panel)
        self.app_addr_edit.setPlaceholderText("App start address (e.g. 0x08002000)")
        self.app_addr_edit.setText("0x08002000")

        actions.addWidget(self.lock_button, 0, 0)
        actions.addWidget(self.nontrusted_button, 0, 1)
        actions.addWidget(self.save_button, 1, 0, 1, 2)
        actions.addWidget(self.app_addr_edit, 2, 0, 1, 2)
        actions.addWidget(self.firmware_update_button, 3, 0, 1, 2)
        panel_layout.addLayout(actions)
        panel_layout.addStretch(1)

        root_layout.addWidget(panel, 2)
        self.setCentralWidget(root)

        self.status = QStatusBar(self)
        self.setStatusBar(self.status)
        self.connection_label = QLabel("Device Connected")
        self.tier_label = QLabel("●")
        self.tier_label.setStyleSheet("font-size: 18px; color: #94A3B8;")
        self.status.addPermanentWidget(self.connection_label)
        self.status.addPermanentWidget(self.tier_label)

    def _bind_signals(self) -> None:
        self.board_widget.button_clicked.connect(self._on_button_selected)
        self.board_widget.calibration_clicked.connect(self._on_calibration_clicked)
        self.lock_button.clicked.connect(self._request_admin)
        self.nontrusted_button.clicked.connect(lambda: self._transport.set_mode(DeviceMode.NON_TRUSTED))
        self.save_button.clicked.connect(self._save_current)
        self.firmware_update_button.clicked.connect(self._flash_firmware)
        self.calibration_toggle_button.clicked.connect(self._toggle_calibration_panel)
        self.rotation_slider.valueChanged.connect(self._on_rotation_changed)
        self.apply_calibration_button.clicked.connect(self._apply_circle_calibration)
        self.export_calibration_button.clicked.connect(self._export_calibration)
        self.import_calibration_button.clicked.connect(self._import_calibration)

        self._transport.status_updated.connect(self._on_status_updated)
        self._transport.connection_changed.connect(self._on_connection_changed)

    def _request_admin(self) -> None:
        dialog = AdminDialog(self)
        if dialog.exec() != QDialog.Accepted:
            return
        if not self._transport.verify_admin(dialog.password):
            reason = self._transport.get_last_event()
            QMessageBox.warning(self, "Admin", reason or "Admin verification failed.")

    def _on_button_selected(self, index: int) -> None:
        self._selected_button = VISUAL_TO_LOGICAL_INDEX[index]
        self.board_widget.set_selected_button(index)
        self._refresh_drawer()

    def _on_calibration_clicked(self, norm_x: float, norm_y: float, idx: int) -> None:
        btn_label = f"{VISUAL_TO_LOGICAL_INDEX[idx] + 1}" if idx >= 0 else "--"
        x_pct = norm_x * 100.0
        y_pct = norm_y * 100.0
        self.coords_label.setText(f"x={x_pct:.2f}%, y={y_pct:.2f}%, btn={btn_label}")

        if idx >= 0:
            self.center_x_inputs[idx].setText(f"{x_pct:.3f}")
            self.center_y_inputs[idx].setText(f"{y_pct:.3f}")

    def _on_rotation_changed(self, value: int) -> None:
        self.rotation_label.setText(f"Rotation: {value}°")
        self.board_widget.set_rotation_degrees(value)

    def _toggle_calibration_panel(self) -> None:
        visible = not self.calibration_panel.isVisible()
        self.calibration_panel.setVisible(visible)
        self.board_widget.set_calibration_enabled(visible)
        self.calibration_toggle_button.setText("Close Calibration" if visible else "Open Calibration")

    def _load_calibration_fields_from_hotspots(self) -> None:
        diameter, centers = self.board_widget.get_circle_params()
        self.diameter_input.setText(f"{diameter * 100.0:.3f}")
        for idx, (center_x, center_y) in enumerate(centers):
            if idx < len(self.center_x_inputs):
                self.center_x_inputs[idx].setText(f"{center_x * 100.0:.3f}")
                self.center_y_inputs[idx].setText(f"{center_y * 100.0:.3f}")

    def _apply_circle_calibration(self) -> None:
        diameter_text = self.diameter_input.text().strip()
        if not diameter_text:
            QMessageBox.warning(self, "Calibration", "Please enter a diameter value in %.")
            return

        try:
            diameter = float(diameter_text) / 100.0
        except ValueError:
            QMessageBox.warning(self, "Calibration", "Invalid diameter value.")
            return

        centers: list[tuple[float, float]] = []
        for idx in range(6):
            x_text = self.center_x_inputs[idx].text().strip()
            y_text = self.center_y_inputs[idx].text().strip()
            if not x_text or not y_text:
                QMessageBox.warning(self, "Calibration", f"Please fill center X/Y for button {idx + 1}.")
                return
            try:
                center_x = float(x_text) / 100.0
                center_y = float(y_text) / 100.0
            except ValueError:
                QMessageBox.warning(self, "Calibration", f"Invalid center value for button {idx + 1}.")
                return
            centers.append((center_x, center_y))

        self.board_widget.set_circle_params(diameter, centers)

    def _export_calibration(self) -> None:
        diameter, centers = self.board_widget.get_circle_params()
        payload = {
            "diameter_percent": round(diameter * 100.0, 3),
            "circles": [
                {
                    "button": VISUAL_TO_LOGICAL_INDEX[idx] + 1,
                    "center_x_percent": round(center_x * 100.0, 3),
                    "center_y_percent": round(center_y * 100.0, 3),
                }
                for idx, (center_x, center_y) in enumerate(centers)
            ],
        }

        file_path, _ = QFileDialog.getSaveFileName(
            self,
            "Export Calibration",
            str(Path.home() / "passpress_calibration.json"),
            "JSON Files (*.json)",
        )
        if not file_path:
            return

        try:
            Path(file_path).write_text(json.dumps(payload, indent=2), encoding="utf-8")
        except OSError as exc:
            QMessageBox.warning(self, "Export", f"Failed to export calibration:\n{exc}")
            return

        QMessageBox.information(self, "Export", "Calibration exported successfully.")

    def _import_calibration(self) -> None:
        file_path, _ = QFileDialog.getOpenFileName(
            self,
            "Import Calibration",
            str(Path.home()),
            "JSON Files (*.json)",
        )
        if not file_path:
            return

        try:
            data = json.loads(Path(file_path).read_text(encoding="utf-8"))
            diameter_percent = float(data["diameter_percent"])
            circles = data["circles"]
            if not isinstance(circles, list) or len(circles) != 6:
                raise ValueError("circles must contain 6 entries")

            self.diameter_input.setText(f"{diameter_percent:.3f}")
            for idx, entry in enumerate(circles):
                cx = float(entry["center_x_percent"])
                cy = float(entry["center_y_percent"])
                self.center_x_inputs[idx].setText(f"{cx:.3f}")
                self.center_y_inputs[idx].setText(f"{cy:.3f}")

            self._apply_circle_calibration()
        except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as exc:
            QMessageBox.warning(self, "Import", f"Failed to import calibration:\n{exc}")
            return

        QMessageBox.information(self, "Import", "Calibration imported and applied.")

    def _save_current(self) -> None:
        if self._status.mode != DeviceMode.ADMIN:
            QMessageBox.information(self, "Read-only", "Admin mode is required to save changes.")
            return
        ok = self._transport.save_button(
            self._selected_button,
            self.password_edit.text(),
            self.append_enter_check.isChecked(),
        )
        if not ok:
            QMessageBox.warning(self, "Save", "Failed to write data to hardware.")

    def _flash_firmware(self) -> None:
        if not self._status.connected:
            QMessageBox.warning(self, "Firmware Update", "Connect the device first.")
            return

        if self._status.mode != DeviceMode.ADMIN:
            QMessageBox.information(self, "Firmware Update", "Admin mode is required before firmware update.")
            return

        addr_text = self.app_addr_edit.text().strip()
        try:
            app_addr = int(addr_text, 0)
        except ValueError:
            QMessageBox.warning(self, "Firmware Update", "Invalid app start address. Use hex (e.g. 0x08002000).")
            return

        file_path, _ = QFileDialog.getOpenFileName(
            self,
            "Select Firmware File",
            str(Path.home()),
            "Firmware Files (*.bin *.elf *.hex)",
        )
        if not file_path:
            return

        progress = QProgressDialog("Uploading firmware...", "", 0, 100, self)
        progress.setWindowTitle("Firmware Update")
        progress.setWindowModality(Qt.WindowModality.WindowModal)
        progress.setMinimumDuration(0)
        progress.setValue(0)

        def on_progress(done: int, total: int) -> None:
            pct = int((done * 100) / total) if total > 0 else 0
            progress.setValue(max(0, min(100, pct)))
            QApplication.processEvents()

        ok = self._transport.upload_firmware(
            file_path,
            app_start_addr=app_addr,
            progress_cb=on_progress,
        )

        progress.setValue(100)

        if ok:
            QMessageBox.information(
                self,
                "Firmware Update",
                "Firmware upload finished. Device should reboot into the new application.",
            )
        else:
            QMessageBox.warning(
                self,
                "Firmware Update",
                f"Firmware upload failed: {self._transport.get_last_event()}",
            )

    def _on_connection_changed(self, connected: bool) -> None:
        self.connection_label.setText("Device Connected" if connected else "Device Disconnected")
        self.connection_label.setStyleSheet("color: #22c55e;" if connected else "color: #ef4444;")
        if not connected:
            self._status = EMPTY_STATUS
            self._set_selected_button_badge_tint("#94A3B8")
            self.password_edit.clear()
            self.append_enter_check.setChecked(False)
            self.password_edit.setReadOnly(True)
            self.save_button.setVisible(False)
        self._update_status_report(self._status)

    def _update_status_report(self, status: DeviceStatus) -> None:
        uptime_seconds = max(0, int(status.uptime_ms / 1000))
        minutes = uptime_seconds // 60
        seconds = uptime_seconds % 60
        uptime_text = f"{minutes:02d}:{seconds:02d}"

        last_button_text = f"{status.last_active_button + 1}" if status.last_active_button >= 0 else "--"
        connection_text = "Connected" if status.connected else "Disconnected"

        self.status_report_label.setText(
            f"Connection: {connection_text}\n"
            f"Reports: {status.report_count}\n"
            f"Uptime: {uptime_text}\n"
            f"Last Active Btn: {last_button_text}\n"
            f"Last Event: {status.last_event}"
        )

    def _set_selected_button_badge_tint(self, color: str) -> None:
        self.button_label.setStyleSheet(
            f"background-color: {color}26; border: 1px solid {color}; border-radius: 12px; padding: 9px 12px; font-size: 14px; font-weight: 650; color: #e2e8f0;"
        )

    def _on_status_updated(self, status: DeviceStatus) -> None:
        self._status = status

        color = MODE_COLORS.get(status.mode, "#94A3B8")
        mode_name = MODE_LABELS.get(status.mode, "Unknown")
        self.mode_badge.setText(f"Mode: {mode_name}")
        self.mode_badge.setStyleSheet(
            f"background-color: {color}22; border: 1px solid {color}; border-radius: 10px; padding: 7px 10px; font-weight: 700; color: {color};"
        )
        self._set_selected_button_badge_tint(color)
        self.tier_label.setStyleSheet(f"color: {color}; font-size: 18px;")
        self._update_status_report(status)

        self._refresh_drawer()

    def _refresh_drawer(self) -> None:
        idx = self._selected_button
        self.board_widget.set_selected_button(LOGICAL_TO_VISUAL_INDEX[idx])
        self.button_label.setText(f"Selected • Button {idx + 1}")

        if not self._status.connected:
            self.password_edit.clear()
            self.password_edit.setReadOnly(True)
            self.append_enter_check.setEnabled(False)
            self.save_button.setVisible(False)
            return

        append = self._status.append_enter[idx]
        raw_value = self._status.passwords[idx]

        cache = getattr(self._transport, "_button_cache", None)
        has_cache = (cache is not None and len(cache) > idx and cache[idx] is not None)
        
        if self._status.mode == DeviceMode.ADMIN and not has_cache:
            if self._transport.get_button_config(idx):
                raw_value = self._status.passwords[idx]
                append = self._status.append_enter[idx]

        if self._status.mode == DeviceMode.NON_TRUSTED:
            self.password_edit.setText("********")
            self.password_edit.setReadOnly(True)
            self.append_enter_check.setChecked(append)
            self.append_enter_check.setEnabled(False)
            self.save_button.setVisible(False)
            return

        if self._status.mode == DeviceMode.TRUSTED:
            self.password_edit.setText(raw_value)
            self.password_edit.setReadOnly(True)
            self.append_enter_check.setChecked(append)
            self.append_enter_check.setEnabled(False)
            self.save_button.setVisible(False)
            return

        self.password_edit.setText(raw_value)
        self.password_edit.setReadOnly(False)
        self.append_enter_check.setChecked(append)
        self.append_enter_check.setEnabled(True)
        self.save_button.setVisible(True)
