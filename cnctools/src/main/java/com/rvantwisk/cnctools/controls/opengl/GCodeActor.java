package com.rvantwisk.cnctools.controls.opengl;

import com.rvantwisk.cnctools.opengl.AbstractActor;
import com.rvantwisk.cnctools.opengl.VBOHelper;
import com.rvantwisk.gcodeparser.*;
import com.rvantwisk.gcodeparser.exceptions.SimException;
import com.rvantwisk.gcodeparser.gcodes.MotionMode;
import com.rvantwisk.gcodeparser.gcodes.Units;
import gnu.trove.list.array.TFloatArrayList;
import org.apache.commons.math3.geometry.euclidean.threed.Rotation;
import org.apache.commons.math3.geometry.euclidean.threed.Vector3D;
import org.lwjgl.opengl.GL11;

import java.util.Map;

/**
 * Render's GCode into OpenGLView
 * Created by rvt on 12/19/13.
 */
public class GCodeActor extends AbstractActor implements MachineController {

    public static double curveSectionMM = 1.0;
    public static double AAXISSTEPDEGREES = 1.0; // When A axis rotaties, simulate it in this number of degrees
    public static int AXISMAXSTEPS = 5000; // When A axis rotates with other axis, limit the number of steps to 5000
    public static double curveSectionInches = curveSectionMM / 25.4;
    private static int ROWSIZE = 7; // coordinates + color 3+4
    final TFloatArrayList data = new TFloatArrayList();
    final MachineStatusHelper machine = new MachineStatusHelper();
    private MotionMode prevMotionMode = MotionMode.G0;
    private double lastX = 0;
    private double lastY = 0;
    private double lastZ = 0;
    private double lastA = 0;

    // USed during rendering
    VBOHelper vboInfo=null;

    public GCodeActor(String name) {
        super(name);
    }

    @Override
    public void startBlock(GCodeParser parser, MachineStatus machineStatus, Map<String, ParsedWord> currentBlock) {
        machine.setMachineStatus(machineStatus);
    }

    private void setMotionColor(final MotionMode m) {
        if (m == MotionMode.G0) {
            data.add(0.87f);
            data.add(0.33f);
            data.add(0.27f);
            data.add(0.5f);
        } else {
            data.add(0.33f);
            data.add(0.27f);
            data.add(0.87f);
            data.add(.5f);
        }
    }

    @Override
    public void endBlock(GCodeParser parser, MachineStatus machineStatus, Map<String, ParsedWord> currentBlock) throws SimException {

        // Set correct color's for current lines
        if (machine.getMotionMode() != prevMotionMode) {
            addData(lastX, lastY, lastZ, prevMotionMode);
            addData(lastX, lastY, lastZ, machine.getMotionMode());
        }

        switch (machine.getMotionMode()) {
            case G0:
            case G1:
                double rX = machine.getX();
                double rY = machine.getY();
                double rZ = machine.getZ();

                addData(rX, rY, rZ, machine.getMotionMode());
                break;
            case G2:
            case G3:
                drawArc(parser, machineStatus, currentBlock);
                break;
        }

        prevMotionMode = machine.getMotionMode();
        lastX = machine.getX();
        lastY = machine.getY();
        lastZ = machine.getZ();
        lastA = machine.getA();
    }

    @Override
    public void end(GCodeParser parser, MachineStatus machineStatus) throws SimException {

    }

    private void addData(double x, double y, double z, MotionMode m) {
        double a = machine.getA();

        int steps = Math.abs((int) (Math.floor(a - lastA) / AAXISSTEPDEGREES));
        steps = Math.min(steps, AXISMAXSTEPS);

        double stepSize = (a - lastA) / steps;
        double stepZSize = (machine.getZ() - lastZ) / steps;
        double stepXSize = (machine.getX() - lastX) / steps;
        double stepYSize = (machine.getY() - lastY) / steps;

        for (int i = 0; i < steps; i++) {

            Vector3D rotatedLoc = new Rotation(new Vector3D(1.0, 0.0, 0.0), lastA / 360.0 * Math.PI * 2.0 + (stepSize * i) / 360.0 * Math.PI * 2.0).applyTo(new Vector3D(lastX + stepXSize * i, lastY + stepYSize * i, lastZ + stepZSize * i));

            data.add((float) (rotatedLoc.getX() + machine.getOX()));
            data.add((float) (rotatedLoc.getY() + machine.getOY()));
            data.add((float) (rotatedLoc.getZ() + machine.getOZ()));

            setMotionColor(machine.getMotionMode());

        }

        Vector3D rotatedLoc = new Rotation(new Vector3D(1.0, 0.0, 0.0), a / 360.0 * Math.PI * 2.0).applyTo(new Vector3D(x, y, z));

        data.add((float) (rotatedLoc.getX() + machine.getOX()));
        data.add((float) (rotatedLoc.getY() + machine.getOY()));
        data.add((float) (rotatedLoc.getZ() + machine.getOZ()));

        setMotionColor(m);

    }

    // This routine was taken from : https://github.com/makerbot/ReplicatorG/blob/master/src/replicatorg/app/gcode/java
    // However this was modified to support P (number of turns)
    // add additional support for G18 and G19 planes
    private void drawArc(GCodeParser parser, MachineStatus machineStatus, Map<String, ParsedWord> currentBlock) throws SimException {


        double curveSection;
        if (machine.getActiveUnit() == Units.G20) {
            curveSection = curveSectionInches;
        } else {
            curveSection = curveSectionMM;
        }

        boolean clockwise = machine.getMotionMode() == MotionMode.G2;

        // angle variables.
        double angleA;
        double angleB;
        double angle;

        // delta variables.
        double aX;
        double aY;
        double bX;
        double bY;

        double i = currentBlock.get("I") == null ? 0.0f : currentBlock.get("I").value;
        double j = currentBlock.get("J") == null ? 0.0f : currentBlock.get("J").value;
        double P = currentBlock.get("P") == null ? 1.0f : currentBlock.get("P").value;
        double z = machine.getZ();

        final double cX = lastX + i;
        final double cY = lastY + j;

        aX = lastX - cX;
        aY = lastY - cY;
        bX = machine.getX() - cX;
        bY = machine.getY() - cY;

        // Clockwise
        if (machine.getMotionMode() == MotionMode.G2) {
            angleA = Math.atan2(bY, bX);
            angleB = Math.atan2(aY, aX);
        } else {
            angleA = Math.atan2(aY, aX);
            angleB = Math.atan2(bY, bX);
        }

        // Make sure angleB is always greater than angleA
        // and if not add 2PI so that it is (this also takes
        // care of the special case of angleA == angleB,
        // ie we want a complete circle)
        if (angleB <= angleA) {
            angleB += 2 * Math.PI * P;
        }
        angle = angleB - angleA;

        // calculate a couple useful things.
        final double radius = Math.sqrt(aX * aX + aY * aY);
        final double length = radius * angle;

        // for doing the actual move.
        int steps;
        int s;

        // Maximum of either 2.4 times the angle in radians
        // or the length of the curve divided by the curve section constant
        steps = (int) Math.ceil(Math.max(angle * 2.4, length / curveSection));


        final double fta;
        if (!clockwise) {
            fta = angleA + angle;
        } else {
            fta = angleA;
        }

        // THis if arc is correct
        // TODO move this into the validator
        final double r2 = Math.sqrt(bX * bX + bY * bY);
        final double percentage;
        if (r2 > radius) {
            percentage = Math.abs(radius / r2) * 100.0;
        } else {
            percentage = Math.abs(r2 / radius) * 100.0;
        }

        if (percentage < 99.9) {
            StringBuilder sb = new StringBuilder();
            sb.append("Radius to end of arc differs from radius to start:\n");
            sb.append("r1=" + radius + "\n");
            sb.append("r2=" + r2 + "\n");
            throw new SimException(sb.toString());
        }

        // this is the real draw action.
        final double arcStartZ = lastZ;
        for (s = 1; s <= steps; s++) {
            int step;
            if (!clockwise)
                step = s;
            else
                step = steps - s;

            final double ta = (angleA + angle * ((double) (step) / steps));

            addData(
                    (cX + radius * Math.cos(ta)),
                    (cY + radius * Math.sin(ta)),
                    (lastZ + (z - arcStartZ) * s / steps), machine.getMotionMode());
        }
    }

    @Override
    public void initialize() {
        vboInfo = VBOHelper.createLines(data.toArray(), data.size() / ROWSIZE, true);
        data.clear();

    }

    @Override
    public void prepare() {

    }

    @Override
    public void draw() {
        GL11.glPushMatrix();
        vboInfo.draw();
        GL11.glPopMatrix();
    }

    @Override
    public void destroy() {
        vboInfo.destroy();
    }
}
