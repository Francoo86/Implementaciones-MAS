import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;
//import com.google.gson.Gson;
import java.nio.file.*;
import java.util.*;

public class Application {
    public static void main(String[] args) {
        try {
            // Start JADE runtime
            jade.core.Runtime runtime = jade.core.Runtime.instance();
            Profile profile = new ProfileImpl();
            profile.setParameter(Profile.MAIN_HOST, "localhost");
            AgentContainer container = runtime.createMainContainer(profile);

            // Read teacher and room data from JSON files
            String teacherJson = new String(Files.readAllBytes(Paths.get("profesores.json")));
            String roomJson = new String(Files.readAllBytes(Paths.get("salas.json")));

            Gson gson = new Gson();
            TeacherData[] teachers = gson.fromJson(teacherJson, TeacherData[].class);
            RoomData[] rooms = gson.fromJson(roomJson, RoomData[].class);

            // Create teacher agents
            for (TeacherData teacher : teachers) {
                AgentController teacherAgent = container.createNewAgent(
                        "Teacher_" + teacher.getRut(),
                        "AgentTeacher",
                        new Object[]{gson.toJson(teacher)}
                );
                teacherAgent.start();
            }

            // Create room agents
            for (RoomData room : rooms) {
                AgentController roomAgent = container.createNewAgent(
                        "Room_" + room.getCode(),
                        "AgentRoom",
                        new Object[]{gson.toJson(room)}
                );
                roomAgent.start();
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}