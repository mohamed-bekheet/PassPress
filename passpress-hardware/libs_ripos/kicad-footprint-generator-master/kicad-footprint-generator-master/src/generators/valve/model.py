#!/usr/bin/python
# -*- coding: utf-8 -*-
#
# This is derived from a cadquery script for generating PDIP models in X3D format
#
# from https://bitbucket.org/hyOzd/freecad-macros
# author hyOzd
#
# Dimensions are from Microchips Packaging Specification document:
# DS00000049BY. Body drawing is the same as QFP generator#
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

__title__ = "make Valve 3D models exported to STEP and VRML"
__author__ = "scripts: Stefan; models: see cq_model files; update: jmwright"
__Comment__ = """This generator loads cadquery model scripts and generates step/wrl files for the official kicad library."""

___ver___ = "2.0.0"

import cadquery as cq

from generators.tools.model import export_tools
from generators.tools.spec.legacy_model_spec import LegacyModelSpec

from .cq_belton_socket import cq_belton_socket
from .cq_dongxin_socket import cq_dongxin_socket
from .cq_parameters_glim import cq_parameters_glim
from .cq_parameters_socket_generic import cq_parameters_socket_generic
from .cq_parameters_tube_CK6418 import cq_parameters_tube_CK6418
from .cq_parameters_tube_generic import cq_parameters_tube_generic


def create_models(spec: LegacyModelSpec, generator_name: str) -> int:
    """Create the 3D models.

    Args:
        spec: The spec of the part(s) to generate.
        generator_name: The name of the generator.

    Returns:
        The number of models generated.
    """
    if "Belton" in spec.spec["model_name"]:
        cqm = cq_belton_socket()
    elif "Dongxin" in spec.spec["model_name"]:
        cqm = cq_dongxin_socket()
    elif "Glimm" in spec.spec["model_name"]:
        cqm = cq_parameters_glim()
    elif "CK6418" in spec.spec["model_name"]:
        cqm = cq_parameters_tube_CK6418()
    elif "Tube" in spec.spec["model_name"]:
        cqm = cq_parameters_tube_generic()
    else:
        cqm = cq_parameters_socket_generic()

    # Make the parts of the model
    (body_top, body, pins, npth_pins) = cqm.make_3D_model(spec.spec)
    body = body.rotate((0, 0, 0), (0, 0, 1), spec.spec["rotation"])
    if body_top:
        body_top = body_top.rotate((0, 0, 0), (0, 0, 1), spec.spec["rotation"])
    if pins:
        pins = pins.rotate((0, 0, 0), (0, 0, 1), spec.spec["rotation"])
    if npth_pins:
        npth_pins = npth_pins.rotate((0, 0, 0), (0, 0, 1), spec.spec["rotation"])

    parts: list[cq.Workplane] = []
    color_names: list[str] = []
    if body is not None:
        parts.append(body)
        color_names.append(spec.spec["body_color_key"])
    if body_top is not None:
        parts.append(body_top)
        color_names.append(spec.spec["body_top_color_key"])
    if pins is not None:
        parts.append(pins)
        color_names.append(spec.spec["pin_color_key"])
    if npth_pins is not None:
        parts.append(npth_pins)
        color_names.append(spec.spec["npth_pin_color_key"])

    export_tools.export(
        generator_name=generator_name,
        lib_name=spec.spec["destination_dir"],
        model_name=spec.spec["model_name"],
        parts=parts,
        color_names=color_names,
    )
    return 1
