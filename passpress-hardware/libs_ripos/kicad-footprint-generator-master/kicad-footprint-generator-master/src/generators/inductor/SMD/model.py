# This is derived from a cadquery script for generating PDIP models in X3D format
#
# from https://bitbucket.org/hyOzd/freecad-macros
# author hyOzd
#
# # Dimensions are from Jedec MS-026D document.
#
## Requirements
## CadQuery 2.1 commit e00ac83f98354b9d55e6c57b9bb471cdf73d0e96 or newer
## https://github.com/CadQuery/cadquery
#
## To run the script just do: ./generator.py --output_dir [output_directory]
## e.g. ./generator.py --output_dir /tmp
#
# * These are cadquery tools to export                                       *
# * generated models in STEP & VRML format.                                  *
# *                                                                          *
# * cadquery script for generating QFP/SOIC/SSOP/TSSOP models in STEP AP214  *
# * Copyright (c) 2015                                                       *
# *     Maurice https://launchpad.net/~easyw                                 *
# * Copyright (c) 2022                                                       *
# *     Update 2022                                                          *
# *     jmwright (https://github.com/jmwright)                               *
# *     Work sponsored by KiCAD Services Corporation                         *
# *          (https://www.kipro-pcb.com/)                                    *
# *                                                                          *
# * All trademarks within this guide belong to their legitimate owners.      *
# *                                                                          *
# *   This program is free software; you can redistribute it and/or modify   *
# *   it under the terms of the GNU General Public License (GPL)             *
# *   as published by the Free Software Foundation; either version 2 of      *
# *   the License, or (at your option) any later version.                    *
# *   for detail see the LICENCE text file.                                  *
# *                                                                          *
# *   This program is distributed in the hope that it will be useful,        *
# *   but WITHOUT ANY WARRANTY; without even the implied warranty of         *
# *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the          *
# *   GNU Library General Public License for more details.                   *
# *                                                                          *
# *   You should have received a copy of the GNU Library General Public      *
# *   License along with this program; if not, write to the Free Software    *
# *   Foundation, Inc.,                                                      *
# *   51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA           *
# *                                                                          *
# ****************************************************************************

__title__ = "make SMD inductors 3D models exported to STEP and VRML"
__author__ = "scripts: maurice; models: see cq_model files; update: jmwright"
__Comment__ = """This generator loads cadquery model scripts and generates step/wrl files for the official kicad library."""

___ver___ = "2.0.0"

import abc

import cadquery as cq

from generators.tools.model import export_tools
from kilibs.declarative_defs.packages.two_pad_dimensions import TwoPadDimensions

from .model_coil import DSectionFootAirCoreCoil
from .spec import (
    CuboidParameters,
    HorizontalAirCoreParameters,
    SmdInductorSpec,
    ShieldedDrumRoundedRectBlockParameters,
)


def create_models(spec: SmdInductorSpec, generator_name: str) -> int:
    """Create the 3D models.

    Args:
        spec: The spec of the part to generate.
        generator_name: The name of the generator.

    Returns:
        The number of models generated.
    """
    model_builder: InductorModelBuilder

    # Dispatch the body type to the appropriate function
    if isinstance(spec.body, CuboidParameters):
        model_builder = CubicInductorBuilder(spec)
    elif isinstance(spec.body, HorizontalAirCoreParameters):
        model_builder = HoriziontalAirCoreBuilder(spec)
    elif isinstance(spec.body, ShieldedDrumRoundedRectBlockParameters):
        model_builder = ShieldedDrumModelBuilder(spec)
    else:
        raise ValueError(f"Invalid body_type: {type(spec.body)}")

    model_parts = model_builder.build()

    parts: list[cq.Workplane] = []
    color_names: list[str] = []

    if model_parts.case is not None:
        parts.append(model_parts.case)
        color_names.append(spec.body_color)
    if model_parts.coil is not None:
        parts.append(model_parts.coil)
        color_names.append(spec.coil_color)  # type: ignore
    if model_parts.pins is not None:
        parts.append(model_parts.pins)
        color_names.append(spec.pad_color)

    export_tools.export(
        generator_name=generator_name,
        lib_name=spec.library_name,
        model_name=f"L_{spec.manufacturer}_{spec.part_number}",
        parts=parts,
        color_names=color_names,
    )
    return 1


def build_pins(
    pin_dims: TwoPadDimensions, pad_thickness: float, body_width: float | None
) -> cq.Workplane:
    """
    Build two simple rectangular pins based on the given dimensions.

    Parameters:
        - pin_dims: TwoPadDimensions object containing the dimensions of the pins.
        - pad_thickness: Thickness of the pads (in z)
        - body_width: Width of the body, used to adjust pin positions if necessary.
    """
    pin1 = (
        cq.Workplane("XY")
        .box(
            pin_dims.size_inline,
            pin_dims.size_crosswise,
            pad_thickness,
            (True, True, False),
        )
        .translate((-pin_dims.spacing_centre / 2, 0, 0))
    )
    pin2 = (
        cq.Workplane("XY")
        .box(
            pin_dims.size_inline,
            pin_dims.size_crosswise,
            pad_thickness,
            (True, True, False),
        )
        .translate((pin_dims.spacing_centre / 2, 0, 0))
    )

    # If the body and pins are the same, bump the pins out a bit so they are definitely visible
    if body_width is not None and abs(body_width - pin_dims.spacing_outside) < 0.01:
        translateAmount = 0.01
        pin1 = pin1.translate((-translateAmount, 0, 0))
        pin2 = pin2.translate((translateAmount, 0, 0))

    return pin1.union(pin2)


class InductorParts:

    def __init__(
        self,
        case: cq.Workplane | None,
        pins: cq.Workplane | None,
        coil: cq.Workplane | None,
    ):
        self.case = case
        """The case of the inductor, if there is one"""
        self.pins = pins
        """The pins of the inductor"""
        self.coil = coil
        """The coil of the inductor, if applicable"""


class InductorModelBuilder(abc.ABC):
    """
    Abstract base class for building inductor models.
    """

    def __init__(self, spec: SmdInductorSpec) -> None:
        self.spec = spec

    @abc.abstractmethod
    def build(self) -> InductorParts:
        """
        Build the inductor model and return the case and pins.
        """
        pass


class CubicInductorBuilder(InductorModelBuilder):

    def __init__(self, spec: SmdInductorSpec) -> None:
        super().__init__(spec)

    def build(self) -> InductorParts:

        body_data = self.spec.body
        assert isinstance(body_data, CuboidParameters)

        # Physical dimensions
        widthX = body_data.width_x
        lengthY = body_data.length_y
        height = body_data.height
        landing = body_data.landing_dims
        pad_dims = body_data.device_pad_dims
        # Handy debug section to help copy/paste into CQ-editor to play with design
        if False:
            print(f"widthX = {widthX}")
            print(f"lengthY = {lengthY}")
            print(f"height = {height}")
            print(f"padX = {pad_dims.size_inline}")
            print(f"padY = {pad_dims.size_crosswise}")
            print(f"landingX = {landing.size_inline}")
            print(f"landingY = {landing.size_crosswise}")
            print(f"seriesType = {series_data.body_type}")
            print(f"seriesPadThickness = {series_data.pad_thickness}")
            print(f"seriesCornerRadius = {series_data.corner_radius}")
        rotation = 0
        case = cq.Workplane("XY").box(widthX, lengthY, height, (True, True, False))

        # If no corner radius, the default is 5%
        corner_fillet_radius = (
            body_data.corner_radius
            if body_data.corner_radius is not None
            else min(lengthY, widthX) / 20
        )

        if corner_fillet_radius > 0:
            case = case.edges("|Z").fillet(corner_fillet_radius)

        # If no top fillet radius, the default is 5%
        top_fillet_radius = (
            body_data.top_fillet_radius
            if body_data.top_fillet_radius is not None
            else min(lengthY, widthX) / 20
        )

        if top_fillet_radius > 0:
            case = case.edges(">Z").fillet(top_fillet_radius)

        if not body_data.bottom_pads:  # Exposed "wings"
            pad_thickness = min(3, height * 0.3)
        else:
            pad_thickness = self.spec.pad_thickness

        pins = build_pins(pad_dims, pad_thickness, widthX)
        case = case.cut(pins)

        case = case.rotate((0, 0, 0), (0, 0, 1), rotation)
        pins = pins.rotate((0, 0, 0), (0, 0, 1), rotation)

        return InductorParts(case, pins, None)


class HoriziontalAirCoreBuilder(InductorModelBuilder):
    """
    Builder for horizontal air core D-section foot inductors.
    """

    def __init__(self, spec: SmdInductorSpec) -> None:
        assert isinstance(spec.body, HorizontalAirCoreParameters)
        assert spec.body.foot_shape == "d_section"

        self.coil_builder = DSectionFootAirCoreCoil(spec.body)

    def build(self) -> InductorParts:
        # Create the coil model
        coil, pins = self.coil_builder.make_coil()
        return InductorParts(None, pins, coil)


class ShieldedDrumModelBuilder(InductorModelBuilder):

    def __init__(self, spec: SmdInductorSpec) -> None:
        super().__init__(spec)

    def build(self) -> InductorParts:

        body = self.spec.body
        assert isinstance(body, ShieldedDrumRoundedRectBlockParameters)

        # Create a baseplate for the inductor to rest on
        baseplate = (
            cq.Workplane("XY")
            .box(
                body.width_x,
                body.length_y,
                1.2,
                (True, True, False),
            )
            .edges("|Z")
            .chamfer((body.length_y - body.device_pad_dims.size_crosswise) / 2)
        )

        # Create the shield that encases the inductor. Start with a cuboid with rounded edges.
        shield = (
            cq.Workplane("XY")
            .workplane(centerOption="CenterOfMass", offset=1.2)
            .box(
                body.width_x,
                body.length_y,
                body.height - 1.2,
                (True, True, False),
            )
            .edges("|Z")
            .fillet(body.corner_radius)
        )
        # Drill a hole in the center and four more into the corners
        # This is a guess and was measured for the MSS110 series
        drill_hole_diam = body.corner_radius
        shield = (
            shield.faces(">Z")
            .workplane()
            .cboreHole(
                body.core_diameter + 0.75,
                cboreDiameter=body.core_diameter + 1.25,
                cboreDepth=0.25,
            )
            .moveTo(0, 0)
            .rect(
                0.5**0.5 * (body.core_diameter + 0.75),
                0.5**0.5 * (body.core_diameter + 0.75),
                centered=True,
                forConstruction=True,
            )
            .vertices()
            .cboreHole(
                drill_hole_diam, cboreDiameter=drill_hole_diam + 0.5, cboreDepth=0.25
            )
        )

        # Add a cap that goes on top of the inductor located in the center.
        # The cap is 0.5 mm in diameter larger than the core, but has a chamfer of 0.5 mm to make it the same size on
        # top. There is also 0.75/2 mm clearance between the cap and the shield filled with glue.
        shield = shield.union(
            cq.Workplane("XY")
            .workplane(centerOption="CenterOfMass", offset=1.2)
            .circle((body.core_diameter + 0.75) / 2)
            .extrude(body.height - 1.2 - 0.5)
            .faces(">Z")
            .workplane()
            .circle(body.core_diameter / 2)
            .workplane(offset=0.5)
            .circle((body.core_diameter - 1) / 2)
            .loft()
        )
        # We neither draw the actual inductor nor its core. So we are done.
        # Merge the baseplate with the shield
        case = shield.union(baseplate)

        # Create the pins
        pins = build_pins(
            body.device_pad_dims,
            self.spec.pad_thickness,
            body.width_x,
        )

        case = case.cut(pins)

        return InductorParts(case, pins, None)
