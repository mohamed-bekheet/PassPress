# generators is free software: you can redistribute it and/or modify it under the terms
# of the GNU General Public License as published by the Free Software Foundation, either
# version 3 of the License, or (at your option) any later version.
#
# generators is distributed in the hope that it will be useful, but WITHOUT ANY
# WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
# PARTICULAR PURPOSE. See the GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License along with
# generators. If not, see < http://www.gnu.org/licenses/ >.
#
# (C) The KiCad Librarian Team

import dataclasses
import enum
from typing import Any

import generators.connector.pin_socket.socket_strips as socket_strips
from generators.tools.spec.base_spec import BaseSpec
from generators.tools.spec.spec_generator import get_spec_dicts


class Orientation(enum.Enum):
    VERTICAL = "vertical"
    HORIZONTAL = "horizontal"


class MountType(enum.Enum):
    THT = "THT"
    SMD = "SMD"


@dataclasses.dataclass
class DParams:
    num_pins: int
    num_pin_rows: int
    pin_pitch: float
    pin_style: Orientation
    pin_length: float
    pin_width: float
    pin_thickness: float
    pin_drill: float
    pin1start_right: bool
    pad_width: float
    pad_length: float
    pads_lp_width: float
    pins_offset: float
    body_width: float
    body_height: float
    body_overlength: float
    body_offset: float
    datasheet: str


class PinSocketProperies:

    def __init__(self, spec: dict[str, Any]):
        match spec["orientation"]:
            case "vertical":
                self.orientation = Orientation.VERTICAL
            case "horizontal":
                self.orientation = Orientation.HORIZONTAL
            case _:
                raise ValueError(f"Invalid orientation: {spec['orientation']}")

        match spec["mount"]:
            case "THT":
                self.mount_type = MountType.THT
            case "SMD":
                self.mount_type = MountType.SMD
            case _:
                raise ValueError(f"Invalid mount type: {spec['mount']}")

        self.row_count = int(spec["row_count"])
        self.pin_pitch = float(spec["pin_pitch"])
        self.row_pitch = float(spec.get("row_pitch", self.pin_pitch))

        pos_range = spec["num_pos"]["range"]
        self.pin_counts = range(pos_range[0], pos_range[1] + 1)

        self.dparams = DParams(
            num_pins=0,  # will be filled in later
            num_pin_rows=self.row_count,
            pin_pitch=self.pin_pitch,
            pin_style=self.orientation,
            pin_length=spec["pins"]["length"],
            pin_width=spec["pins"]["width"],
            pin_thickness=spec["pins"]["thickness"],
            pin_drill=spec["pins"]["drill"],
            pin1start_right=spec.get("pin1start_right", False),
            pad_width=spec["pads"]["width"],
            pad_length=spec["pads"]["length"],
            pads_lp_width=spec["pads"].get("lp_width", spec["pads"]["width"]),
            pins_offset=spec["pins"].get("offset", 0.0),
            body_width=spec["body"]["width"],
            body_height=spec["body"]["height"],
            body_overlength=spec["body"].get("overlength", 0.0),
            body_offset=spec["body"].get("offset", 0.0),
            datasheet=spec.get("datasheet", ""),
        )


def create_footprints(spec: BaseSpec, generator_name: str) -> int:
    """Create the footprint(s) corresponding to the spec.

    Args:
        spec: The specification (not used by this generator).
        generator_name: The name of this generator.

    Returns:
        The number of footprints generated.
    """
    import generators.connector.Phoenix_SPT.phoenixcontact_terminal_block_spt_tht as con

    num_fps_generated = 0
    spec_dicts = get_spec_dicts(generator_name)
    # Create each part
    for _, yaml in spec_dicts:
        for _, spec in yaml.items():
            fp_config = PinSocketProperies(spec)
            for pin_count in fp_config.pin_counts:
                fp_config.dparams.num_pins = pin_count
                if (
                    fp_config.mount_type == MountType.THT
                    and fp_config.orientation == Orientation.VERTICAL
                ):
                    builder = socket_strips.pinSocketVerticalTHT(fp_config.dparams)
                elif (
                    fp_config.mount_type == MountType.THT
                    and fp_config.orientation == Orientation.HORIZONTAL
                ):
                    builder = socket_strips.pinSocketHorizontalTHT(fp_config.dparams)
                elif (
                    fp_config.mount_type == MountType.SMD
                    and fp_config.orientation == Orientation.VERTICAL
                ):
                    builder = socket_strips.pinSocketVerticalSMD(fp_config.dparams)
                # elif fp_config.mount == "SMD" and fp_config.orientation == Orientation.HORIZONTAL:
                #     builder = socket_strips.pinSocketHorizontalSMD(fp_config.dparams)
                else:
                    raise ValueError(
                        f"Unsupported mount/orientation combination: {fp_config.mount_type}/{fp_config.orientation}"
                    )
                builder.make(generator_name)
                num_fps_generated += 1
    return num_fps_generated
