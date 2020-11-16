import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.sql.*;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.*;


public class QueryMaker {
    public static int DATE = 3;
    public static int DOUBLE = 2;
    public static int INT = 1;
    public static int STRING = 0;
    private static Connection connection;
    private PreparedStatement preparedStatement;
    public static Statement statement;
    private String tableName;



    /**
     * Creates QueryMaker object that is used to interact with MySQL Database
     *
     * @param userName     MySQL userName
     * @param password     MySQL password
     * @param ipAddress    MySQL IP Address
     * @param portNumber   SQL portNumber
     * @param databaseName MySQL database name
     * @throws ClassNotFoundException
     * @throws SQLException
     */

    public QueryMaker(String userName, String password, String ipAddress, String portNumber, String databaseName) throws ClassNotFoundException, SQLException {

        Class.forName("com.mysql.cj.jdbc.Driver");
        String getURL = "jdbc:mysql://" + ipAddress + ":" + portNumber + "/" + databaseName;
        connection = DriverManager.getConnection(getURL, userName, password);
        statement = connection.createStatement();
        //System.out.println("Connection Succesful");
    }

    /**
     * transfers unprocessed customer_orders csv file into the unprocessed_sales SQL table
     * add an additional column to unprocessed_sales table for hashing emails
     * load and hash customer emails in hash_ref table from unprocessed_sales table
     * add newly hashed emails into the unprocessed_sales table from hash_ref.
     * DELETE un-hashed emails column from unprocessed_sales
     * We now have a SQL table with unprocessed sales and hashed customer emails
     *
     * @throws SQLException
     * @throws FileNotFoundException
     * @throws ClassNotFoundException
     */

    public void batchLoading(String customer_orders_file, String dim_date_start, String dim_date_end) throws SQLException, FileNotFoundException, ClassNotFoundException {

        // Step 1: Load SQL unprocessed_sales table with Java 2-D Array using .csv file for data source.
        Object[][] objArr = this.csvToArray(customer_orders_file, new int[]{DATE, STRING, STRING, STRING, INT});
        this.setTableName("temp_unprocessed_sales");
        this.insertRows(new String[]{"date", "cust_email", "cust_location", "product_id", "product_quantity"}, objArr);

        // Step 2: Load dim_date with desired date range.
        statement.execute(" SET @startDate = '" + dim_date_start + "'");
        statement.execute(" SET @endDate = '" + dim_date_end + "'");
        statement.execute(" Call TEAM_6_DB.create_dimdate(@startDate, @endDate) ");

        // Step 3: Load dim_supplier with supplier_id from temp_inventory.
        statement.executeUpdate("INSERT INTO dim_supplier (supplier_id) " +
                "SELECT DISTINCT supplier_id FROM temp_inventory ");

        // Step 4: Load dim_product with product_id and supplier_id from temp_inventory.
        statement.executeUpdate("INSERT INTO dim_product (product_id, supplier_id) " +
                "SELECT DISTINCT product_id, supplier_id FROM temp_inventory ");

        // Step 5: Load dim_product with supplier_tid
        statement.executeUpdate("UPDATE dim_product dp, dim_supplier ds " +
                " SET dp.supplier_tid = ds.supplier_tid " +
                " WHERE dp.supplier_id = ds.supplier_id ");

        // Step 6: Remove supplier_id column from dim_product table.
        statement.executeUpdate("ALTER TABLE dim_product DROP COLUMN supplier_id ");

        // Step 7: Load inventory from dim_product, dim_supplier, and temp_inventory
        statement.executeUpdate("INSERT INTO inventory " +
                "SELECT dp.product_tid, ti.quantity, ti.wholesale_cost, ti.sale_price, ds.supplier_tid " +
                "FROM temp_inventory ti " +
                "INNER JOIN dim_product dp ON ti.product_id = dp.product_id " +
                "INNER JOIN dim_supplier ds ON ti.supplier_id = ds.supplier_id ");

        // Step 8: Drop temp_inventory table (no longer needed).
        deleteTable("temp_inventory");

        // Step 9: Add column to temp_unprocessed_sales table for hashed email.
        statement.executeUpdate("ALTER TABLE temp_unprocessed_sales ADD COLUMN hashed_email VARBINARY(32)");

        // Step 10: Load hash table with emails from unprocessed orders and generate hashed emails.
        statement.executeUpdate("INSERT INTO hash_ref (hashed_email, unhashed_email) " +
                "SELECT DISTINCT MD5(cust_email), cust_email FROM temp_unprocessed_sales");

        // Step 11: Fill with hashed emails from hash table.
        this.updateTableFromTable("temp_unprocessed_sales", "hash_ref", "hashed_email",
                "hashed_email", "cust_email", "unhashed_email");

        // Step 12: Delete (unhashed) email column from unprocessed orders.
        statement.executeUpdate("ALTER TABLE temp_unprocessed_sales DROP COLUMN cust_email");

        //Step 13: Load temp_unprocessed_sales into unprocessed_sales table
        statement.executeUpdate("INSERT INTO unprocessed_sales " +
                "SELECT dd.date_id, tus.cust_location, dp.product_tid, tus.product_quantity, tus.hashed_email " +
                "FROM temp_unprocessed_sales tus " +
                "INNER JOIN dim_date dd ON tus.date = dd.date_date " +
                "INNER JOIN dim_product dp ON tus.product_id = dp.product_id ");

        //Step 14: Drop temp_unprocessed_sales table.
        deleteTable("temp_unprocessed_sales");

    }

    /**
     * convert inventory table/unprocessed_sales into java readable format and load a hash map
     * update product quantity in inventory based on unprocessed_sales order amount
     * if the unprocessed_sales quantity is > inventory quantity then sale cannot be processed. (negative inventory)
     * Discard any untouched product quantities. (Eliminates any duplicates)
     * Load the inventory SQL table with new changes to quantity.
     * Create a new date column and add to the processed_sales table
     * load processed_sales with the information below
     * date, processed_datetime, un-hashed_email, cust_location, product_id, product_quantity, result
     * We now have a table with ALL the information we need for analytics.
     *
     * @throws SQLException
     */

    public void batchProcessing(int resupply_quantity) throws SQLException {
        // Step 1: Pull inventory table into Java data structure.
        ResultSet inv = statement.executeQuery("SELECT product_tid, quantity FROM inventory ");
        HashMap<Integer, Integer> invHashMap = new HashMap<>();
        Integer inv_p_tid;
        Integer inv_quant;

        while (inv.next()) {
            inv_p_tid = Integer.valueOf(inv.getInt(1));
            inv_quant = Integer.valueOf(inv.getInt(2));
            invHashMap.put(inv_p_tid, inv_quant);
        }
        HashMap<Integer, Integer> invHashMap_original = new HashMap<>();
        invHashMap_original.putAll(invHashMap);

        // Step 2: Pull unprocessed sales into Java data structure
        ResultSet us = statement.executeQuery("SELECT date_id, cust_location, product_tid, quantity, hashed_email " +
                "FROM unprocessed_sales AS us " +
                "ORDER BY date_id, us.hashed_email");
        int us_date;
        String us_loc;
        int us_p_tid;
        int us_quant;
        String us_email;
        List<Transaction> usList = new ArrayList<>();

        while (us.next()) {
            us_date = us.getInt(1);
            us_loc = us.getString(2);
            us_p_tid = us.getInt(3);
            us_quant = us.getInt(4);
            us_email = us.getString(5);

            Transaction transaction = new Transaction(us_date, us_loc, us_p_tid, us_quant, us_email);
            usList.add(transaction);
        }

        // Step 3: Iteratively compare batch orders to inventory, updating inventory & processed sales Java structures
        int inv_q;
        int us_q;
        List<String[]> psList = new ArrayList<>();

        for (Transaction t : usList) {
            us_q = t.getQuantity();
            inv_q = invHashMap.get(Integer.valueOf(t.getProductID()));

            if (us_q <= inv_q) {
                invHashMap.put(Integer.valueOf(t.getProductID()), inv_q - us_q); //enough inventory in stock; process sale
                psList.add(t.processTransaction(1));
            } else {
                psList.add(t.processTransaction(0)); //not enough inventory in stock; order more from supplier
                invHashMap.put(Integer.valueOf(t.getProductID()), resupply_quantity);
                ResultSet s_tid_rs = statement.executeQuery("SELECT supplier_tid FROM dim_supplier " +
                        "WHERE product_tid = " + t.getProductID() );
                int s_tid = 0;
                while (s_tid_rs.next())
                    s_tid = s_tid_rs.getInt(1);
                statement.executeUpdate("INSERT INTO supplier_orders " +
                        "VALUES( " + t.getDate() + " , " + s_tid + " , " + t.getProductID() + " , " + resupply_quantity + " )");
            }
        }

        //Step 4: Remove duplicates (unchanged quantities) from hash map to go into inventory SQL table.
        Iterator iter = invHashMap_original.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry p = (Map.Entry) iter.next();
            if (invHashMap.get(p.getKey()) == p.getValue())
                invHashMap.remove(p.getKey());
        }

        //Step 6: Create indexed tables to house new inventory and historic inventory values on SQL server side
        createTable("temp_inventory",
                "product_tid INT, quantity INT, INDEX temp_product_id_index (product_tid)");

        // Step 7: Prepare SQL statement with updated inventory and historic inventory values from Java data structure.
        Iterator it = invHashMap.entrySet().iterator();
        LinkedList<Object[]> inv_Array = new LinkedList<>();

        while (it.hasNext()) {
            Object[] inv_Arr = new String[2];
            Map.Entry pair = (Map.Entry) it.next();
            inv_Arr[0] = pair.getKey().toString();
            inv_Arr[1] = pair.getValue().toString();
            inv_Array.add(inv_Arr);
        }

        String[] inv_headers = {"product_tid", "quantity"};
        Object[][] inv_objects = new Object[inv_Array.size()][inv_headers.length];
        for (int j = 0; j < inv_objects.length; j++) {
            Object[] element = inv_Array.pop();
            for (int i = 0; i < inv_headers.length; i++) {
                inv_objects[j][i] = element[i];
            }
        }

        //Step 8: Insert Rows into temp_inventory SQL tables
        this.setTableName("temp_inventory");
        this.insertRows(inv_headers, inv_objects);

        //Step 9: Optimize batch processing by indexing the temporary table on SQL server side.
//        statement.executeUpdate("ALTER TABLE temp_inventory ADD INDEX `temp_product_id_index` (`product_id`) ");

        //Step 10: Alter inventory & historic inventory with new temporary values
        updateTableFromTable("inventory", "temp_inventory",
        "quantity", "quantity",
                "product_tid", "product_tid");

        //Step 11: Construct second parameter of InsertRows method (2-D Object Array)
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        LinkedList<Object[]> tempArray = new LinkedList<>();

        for (String[] s : psList) {
            Object[] strArr = new String[7];

            strArr[0] = valueQueryPrep(Integer.parseInt(s[0]));
            strArr[1] = valueQueryPrep(LocalDateTime.parse(s[1], formatter));
            strArr[2] = valueQueryPrep(s[2]);
            strArr[3] = valueQueryPrep(Integer.parseInt(s[3]));
            strArr[4] = valueQueryPrep(Integer.parseInt(s[4]));
            strArr[5] = valueQueryPrep(Integer.parseInt(s[5]));
            strArr[6] = valueQueryPrep(s[6]);
            tempArray.add(strArr);
        }

        String[] headers = {"date_id", "processed_dt", "cust_location", "product_tid", "quantity", "result", "hashed_email"};
        Object[][] objects = new Object[tempArray.size()][headers.length];
        for (int j = 0; j < objects.length; j++) {
            Object[] element = tempArray.pop();
            for (int i = 0; i < headers.length; i++) {
                objects[j][i] = element[i];
            }
        }

        //Step 12: Insert Rows into processed_sales SQL table
        this.setTableName("processed_sales");
        this.insertRows(headers, objects);

        //Step 13: Truncate unprocessed_sales table.
        statement.executeUpdate("TRUNCATE unprocessed_sales");


        //Step 16: Delete the temporary inventory and temporary historic inventory tables
        deleteTable("temp_inventory");

    }

    /**
     * Takes in a inventory csv file and formats the file information to be recognized by SQL
     * creates four tables: inventory, unprocessed_sales, hash_ref and processed_sales
     * inventory table is loaded with csv file information
     * all other tables have specified columns created, are empty and ready to use
     *
     * @throws SQLException
     * @throws FileNotFoundException
     */

    // This method takes in a .csv file and turns it into a 2D array with the specified column types.
    // Making the format compatible for SQL to read
    public void createDatabaseStructure(String inventory_file) throws SQLException, FileNotFoundException {
        // Step 1: Create temp_inventory table in SQL Database.
        createTable("temp_inventory",
                "product_id VARCHAR(12),quantity INT,wholesale_cost DECIMAL(12,2),sale_price DECIMAL(12,2),supplier_id VARCHAR(8)" +
                        ", INDEX t_inv_index (product_id) ");

        // Step 2: Load SQL temp_inventory table with Java 2-D Array.

        // Step 3: Create inventory table in SQL database
        createTable("inventory",
                "product_tid int, quantity INT, wholesale_cost DECIMAL(12,2), sale_price DECIMAL(12,2), supplier_tid INT" +
                        ", INDEX inv_index (product_tid) ");

        // Step 4: Create dim_supplier table in SQL database
        createTable("dim_supplier",
            "supplier_tid INT NOT NULL AUTO_INCREMENT, supplier_id VARCHAR(8), INDEX ds_index (supplier_tid)");

        // Step 5: Create dim_product table in SQL database
        createTable("dim_product",
                "product_tid INT NOT NULL AUTO_INCREMENT, supplier_tid INT, supplier_id VARCHAR(8), product_id VARCHAR(12), INDEX dp_index (product_tid)");

        // Step 6: Create temp_unprocessed_sales table in SQL database.
        createTable("temp_unprocessed_sales",
                "date DATE,cust_email VARCHAR(320),cust_location VARCHAR(5),product_id VARCHAR(12),product_quantity int");

        // Step 6: Create unprocessed_sales table in SQL Database.
        createTable("unprocessed_sales",
                "date_id INT, cust_location VARCHAR(5), product_tid INT, quantity INT, hashed_email VARBINARY(32)");

        // Step 7: Create hash_ref table in SQL Database.
        createTable("hash_ref",
                "hashed_email VARBINARY(32),unhashed_email VARCHAR(320), INDEX hr_index (hashed_email) ");

        // Step 8: Create processed_sales table in SQL Database.
        createTable("processed_sales",
                "date_id INT,processed_dt DATETIME, cust_location VARCHAR(5),product_tid INT,quantity INT," +
                        "result TINYINT, hashed_email VARBINARY(32), INDEX ps_index (date_id, product_tid)");

        // Step 9: Create dim_date table in SQL database.
        createTable("dim_date",
                "date_id INT NOT NULL AUTO_INCREMENT, date_date DATE, INDEX dd_index (date_id)");

        // Step 10: Create supplier_orders table in SQL database.
        createTable("supplier_orders",
                "date_id INT, supplier_tid INT, product_tid INT, quantity INT, INDEX so_index (supplier_tid) ");

        // Step 10: Load temp_inventory table with Java 2-D Array using .csv file for data source.
        Object[][] objArr = this.csvToArray(inventory_file, new int[]{STRING, INT, DOUBLE, DOUBLE, STRING});
        this.setTableName("temp_inventory");
        this.insertRows(new String[]{"product_id", "quantity", "wholesale_cost", "sale_price", "supplier_id"}, objArr);
    }

    public void createHistInv() throws SQLException {
        // Step 7: Create dimension date table; create historical inventory table and perform initial load.
        statement.execute("CALL TEAM_6_DB.datesRefAndHistInv()");
    }


    /**
     * creates a new table with the following two arguments:
     * NOTE - method checks and deletes if the table already exists first
     *
     * @param tableName   - name of table name
     * @param columnSpecs - name the columns and a variable type (int, DATE, VARCHAR etc..)
     * @throws SQLException
     */

    public void createTable(String tableName, String columnSpecs) throws SQLException {
        this.deleteTable(tableName);
        statement.executeUpdate("CREATE TABLE " + tableName + " ( " + columnSpecs + " )");
    }

    /**
     * takes in a csv file and formats it to be SQL recognizable (quote wrapping) using the following two arguments:
     *
     * @param fileName - csv filename
     * @param types    - integer array of types (STRING, DATE, INT etc..)
     * @return - 2D array of SQL format readable information from the csv file
     * @throws FileNotFoundException
     */

    public Object[][] csvToArray(String fileName, int[] types) throws FileNotFoundException {
        Scanner fileReader = new Scanner(new File(fileName));
        String[] headers = fileReader.nextLine().split(",");
        LinkedList<String[]> arrays = new LinkedList<>();

        for (int j = 0; fileReader.hasNextLine(); j++) {
            arrays.add(fileReader.nextLine().split(","));

        }
        Object[][] objects = new Object[arrays.size()][headers.length];
        for (int j = 0; j < objects.length; j++) {
            String[] element = arrays.remove();
            for (int i = 0; i < headers.length; i++) {

                objects[j][i] = types[i] == STRING || types[i] == DATE ?
                        "'" + element[i] + "'"
                        : types[i] == INT ?
                        Integer.parseInt(element[i])
                        : Double.parseDouble(element[i]);
            }
        }
        return objects;
    }

    /**
     * deletes any values from a table based on three of the following arguments:
     *
     * @param tableName  - name of table to delete from
     * @param columnName - name of column within the table
     * @param value      - deletes all rows with the DECIMAL value matching that of the search
     * @throws SQLException
     */

    public void deleteRecords(String tableName, String columnName, double value) throws SQLException {
        statement.executeUpdate("DELETE FROM " + tableName + "WHERE " + columnName + " = " + valueQueryPrep(value));
    }

    /**
     * deletes any values from a table based on three of the following arguments:
     *
     * @param tableName  - name of table to delete from
     * @param columnName - name of column within the table
     * @param value      - deletes all rows with the INTEGER value matching that of the search
     * @throws SQLException
     */

    public void deleteRecords(String tableName, String columnName, int value) throws SQLException {
        statement.executeUpdate("DELETE FROM " + tableName + "WHERE " + columnName + " = " + valueQueryPrep(value));
    }

    /**
     * deletes any values from a table based on three of the following arguments:
     *
     * @param tableName  - name of table to delete from
     * @param columnName - name of column within the table
     * @param value      - deletes all rows with the STRING value matching that of the search
     * @throws SQLException
     */

    public void deleteRecords(String tableName, String columnName, String value) throws SQLException {
        statement.executeUpdate("DELETE FROM " + tableName + "WHERE " + columnName + " = " + valueQueryPrep(value));
    }

    /**
     * deletes all rows given the table name but preserves the table
     *
     * @throws SQLException
     */

    public void deleteRows() throws SQLException {
        generateUpdate("DELETE FROM " + tableName);
    }

    /**
     * deletes the entire table given the table name
     *
     * @param tableName
     * @throws SQLException
     */

    public void deleteTable(String tableName) throws SQLException {
        statement.executeUpdate("DROP TABLE IF EXISTS " + tableName);
    }

    /**
     * deletes the table based on the following arguments:
     *
     * @param tableName - name of table
     * @param condition - this condition must be met
     * @throws SQLException
     */

    public void deleteTableWithCond(String tableName, String condition) throws SQLException {
        statement.executeUpdate("DROP TABLE IF EXISTS " + tableName + " " + condition);
    }

    /**
     * call setTableName() to proceed to use this method
     * displays the information in the IDE console from the SQL table
     *
     * @throws SQLException
     */

    private void displayFile() throws SQLException {
        ResultSet rs = generateQuery("SELECT * FROM " + tableName);
        while (rs.next()) {
            for (int i = 0; i < rs.getMetaData().getColumnCount(); i++) {
                System.out.print(rs.getObject(i + 1));
                if (i < rs.getMetaData().getColumnCount() - 1) {
                    System.out.print(",");
                }
            }
            System.out.println();
        }
    }

    /**
     * helper method for getProduct() method
     * returns a 2D object array of information taking the following arguments
     *
     * @param rs          - must provide a result set
     * @param isOneColumn - boolean value if the extracted information is one column or greater
     * @return 2D object results of data
     * @throws SQLException
     */

    public Object[][] extractResults(ResultSet rs, Boolean isOneColumn) throws SQLException {
        ArrayList<Object[]> temp = new ArrayList<>();
        int columnCount = rs.getMetaData().getColumnCount();
        for (int j = 0; rs.next(); j++) {
            Object[] objects = new Object[columnCount];
            for (int i = 0; i < columnCount; i++) {
                objects[i] = rs.getObject(i + 1);
            }
            temp.add(objects);
        }
        Object[][] data = new Object[temp.size()][columnCount];
        Iterator<Object[]> it = temp.iterator();
        for (int i = 0; i < data.length; i++) {
            data[i] = it.next();
        }
        return data;
    }

    /**
     * given an argument (any SQL statement) it will return a table of useful data
     *
     * @param s - any SQL syntax commands
     * @return returns a table of data that is scrollable
     * @throws SQLException
     */

    public ResultSet generateQuery(String s) throws SQLException {
        Statement st = connection.createStatement(
                ResultSet.TYPE_SCROLL_INSENSITIVE,
                ResultSet.CONCUR_UPDATABLE);
        return st.executeQuery(s);

    }

    /**
     * given an argument (any SQL statement) it will update any changes to a specified target
     *
     * @param s - any SQL syntax commands
     * @throws SQLException
     */

    public void generateUpdate(String s) throws SQLException {
        Statement st = connection.createStatement(
                ResultSet.TYPE_SCROLL_INSENSITIVE,
                ResultSet.CONCUR_UPDATABLE);
        st.execute(s);
    }

    /**
     * call setTableName() to proceed to use this method
     * based on the table name we can return the column names of that table
     *
     * @return tables column names
     * @throws SQLException
     */

    public String[] getColumnNames() throws SQLException {
        ResultSet rs = generateQuery("SELECT `COLUMN_NAME` FROM `INFORMATION_SCHEMA`.`COLUMNS` WHERE `TABLE_SCHEMA`='TEAM_6' AND `TABLE_NAME`='" + this.tableName + "'");
        String[] columnNames = new String[rowCountResults(rs)];
        for (int j = 0; rs.next(); j++) {
            columnNames[j] = rs.getString(1);
        }
        return columnNames;
    }

    /**
     * uses the extractResults() method to help return the information searched for
     * returns the information searched for given the following arguments below
     *
     * @param tableName   - specify the name of the table
     * @param columnName  - specify the name of the column within the table
     * @param columnValue - specify any object type searched for (int, String etc)
     * @return calls on a helper method to return a nice table of information
     * @throws SQLException
     */

    public Object[][] getProduct(final String tableName, final String columnName, Object columnValue) throws SQLException {
        ResultSet rs = generateQuery("SELECT * FROM " + tableName + " WHERE " + columnName + " = " + quoteWrap(columnValue));
        String s = "select count(*) from " + tableName + " where " + columnName + " = " + getString(columnValue);
        ResultSet resultCount = generateQuery(s);
        return extractResults(rs, true);
    }

    /**
     * quote wraps a specified String or Date based on the argument below
     *
     * @param columnValue - any column containing a String or Date will be wrapped in quotes for SQL syntax
     * @return
     */

    private String getString(Object columnValue) {
        if (columnValue instanceof String || columnValue instanceof LocalDate) {
            return "'" + columnValue + "'";
        }
        return columnValue.toString();
    }

    /**
     * getter method for returning the table name
     *
     * @return - any table name found in the database
     */

    public String getTableName() {
        return tableName;
    }

    /**
     * call setTableName() to proceed to use this method
     * loads specified rows of information in a table given the following arguments
     *
     * @param columnNames - names of all the columns
     * @param rows        - 2D array of all the information to add
     * @throws SQLException
     */

    public void insertRows(String[] columnNames, Object[][] rows) throws SQLException {

        StringBuilder builder = new StringBuilder();
        String s = Arrays.toString(columnNames);
        builder.append(" INSERT INTO " + tableName + " ( " + s.substring(1, s.length() - 1) + " ) VALUES ");
        for (int i = 0; i < rows.length; i++) {

            String s1 = Arrays.deepToString(rows[i]);
            builder.append(" ( " + s1.substring(1, s1.length() - 1) + " )");
            if (i < rows.length - 1) {
                builder.append(",");
            }
        }
        generateUpdate(builder.toString());
    }

    /**
     * add new information into a table with unspecified column given the two following arguments
     *
     * @param tableName - name of the table
     * @param values    - the values to be added
     * @throws SQLException
     */

    public void insertRecordIntoTable(String tableName, String values) throws SQLException {
        statement.executeUpdate("INSERT INTO " + tableName + " VALUES ( " + values + " ) ");
    }

    /**
     * add new information into a table with the specified column name given the three following arguments
     *
     * @param tableName   - name of table
     * @param columnNames - name of column in the table
     * @param values      - the values to be added
     * @throws SQLException
     */

    public void insertValuesIntoTable(String tableName, String columnNames, String values) throws SQLException {
        statement.executeUpdate("INSERT INTO " + tableName + " ( " + columnNames + " ) VALUES ( " + values + " ) ");
    }

    /**
     * If the information is of type String or LocalDate then it will be formatted for SQL readable syntax
     *
     * @param columnValue - value must be of type String or LocalDate
     * @return returns a string of SQL friendly syntax
     */
    private String quoteWrap(Object columnValue) {
        if (columnValue instanceof String || columnValue instanceof LocalDate) {
            return "'" + columnValue + "'";
        } else {
            return columnValue + "";
        }
    }

    /**
     * searches a table for specific information and returns any results given the following arguments
     *
     * @param tableName   - name of table
     * @param whereClause - what user is searching for
     * @param value       - what user search is checked against
     * @return return a table of data
     * @throws SQLException
     */

    //use when needing (up to) all columns from result
    public ResultSet readRecords(String tableName, String whereClause, String value) throws SQLException {
        value = valueQueryPrep(value);
        String query = "SELECT * FROM " + tableName + " WHERE " + whereClause + " = " + value;
        preparedStatement = connection.prepareStatement(query);
        ResultSet rs = preparedStatement.executeQuery();
        return rs;
    }

    /**
     * reads a table based on the argument below:
     *
     * @param tableName - name of table
     * @return return a table of data - result set
     * @throws SQLException
     */

    public ResultSet readTable(String tableName) throws SQLException {
        ResultSet rs = statement.executeQuery("SELECT * FROM " + tableName);
        return rs;

    }

    /**
     * reads a table based on a given condition
     *
     * @param tableName - name of table
     * @param condition - a condition can be weather something is true for false or (0 or 1)
     * @return a table of data - result set
     * @throws SQLException
     */

    public ResultSet readTableWithCond(String tableName, String condition) throws SQLException {
        ResultSet rs = statement.executeQuery("SELECT * FROM " + tableName + " " + condition);
        return rs;
    }

    /**
     * searches a table for a value based on column name
     *
     * @param columnName  - name of column from table
     * @param tableName   - name of table
     * @param whereClause - what the user is searching for in the column
     * @return returns a column of data
     * @throws SQLException
     */

    //use when assuming needing all matches for one column
    public ResultSet readValues(String columnName, String tableName, String whereClause) throws SQLException {
        ResultSet rs = statement.executeQuery("SELECT " + columnName + " FROM " + tableName + " WHERE " + whereClause);
        return rs;
    }

    /**
     * returns the number of rows in the result set
     *
     * @param rs - specify a result set
     * @return return the number or rows in that result set
     * @throws SQLException
     */
    int rowCountResults(ResultSet rs) throws SQLException {
        rs.last();
        int countRows = rs.getRow();
        rs.beforeFirst();
        return countRows;
    }

    /**
     * setter method for table name
     * ALWAYS SET THE TABLE NAME BEFORE PROCEEDING WITH ANYTHING ELSE
     *
     * @param tableName - String name given from user
     */
    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    /**
     * creates a new temporary table that will fill information on the top ten customers
     * takes on the customer information: date , customer email, product ID, total purchased.
     *
     * @throws SQLException
     */

    public void topTenCustomers() throws SQLException {
        statement.execute("DROP TABLE IF EXISTS temp");
        this.createTable("temp", "date date, cust_email VARCHAR(320), product_id VARCHAR(12), Total_Purchase DECIMAL (64, 2)");
        statement.executeUpdate("INSERT INTO temp ");

    }

    /**
     * populate one tables information into another with the original tables information
     *
     * @param tableName1        - original table name
     * @param tableName2        - new table name
     * @param setColumnNameT1   - first column name
     * @param setColumnNameT2   - second column name
     * @param whereColumnNameT1 - old column name 1
     * @param whereColumnNameT2 - old column name 2
     * @throws SQLException
     */

    public void updateTableFromTable(String tableName1, String tableName2, String setColumnNameT1, String setColumnNameT2, String whereColumnNameT1, String whereColumnNameT2) throws SQLException {
        statement.executeUpdate("UPDATE " + tableName1 + ", " + tableName2 + " " +
                "SET " + tableName1 + "." + setColumnNameT1 + " = " + tableName2 + "." + setColumnNameT2 + " " +
                "WHERE " + tableName1 + "." + whereColumnNameT1 + " = " + tableName2 + "." + whereColumnNameT2);
    }

    /**
     * populate one tables information into another with the original tables information such that a condition is met
     *
     * @param tableName1        - original table name
     * @param setColumnNameT1   - new column name
     * @param value             - values to be added
     * @param whereColumnNameT1 - old column name
     * @param condition         - may be true false condition
     * @throws SQLException
     */

    public void updateTableFromStatic(String tableName1, String setColumnNameT1, String value, String whereColumnNameT1, String condition) throws SQLException {
        statement.executeUpdate("UPDATE " + tableName1 +
                " SET " + tableName1 + "." + setColumnNameT1 + " = " + valueQueryPrep(value) + " " +
                " WHERE " + tableName1 + "." + whereColumnNameT1 + " = " + valueQueryPrep(condition));
    }

    /**
     * searches a table for a DECIMAL value and returns true/false based on the given arguments below:
     *
     * @param columnName - name of column
     * @param tableName  - name of table
     * @param value      - the value being searched by the user
     * @return
     * @throws SQLException
     */


    public Boolean valueExists(String columnName, String tableName, double value) throws SQLException {
        ResultSet rs = statement.executeQuery("SELECT COUNT( " + columnName + " ) FROM " + tableName + " WHERE " + columnName + " = " + valueQueryPrep(value));
        int temp = 0;
        while (rs.next()) {
            temp++;
        }
        return temp > 0;
    }

    /**
     * searches a table for an INTEGER value and returns true/false based on the given arguments below:
     *
     * @param columnName - name of column
     * @param tableName  - name of table
     * @param value      - the value being searched by the user
     * @return
     * @throws SQLException
     */

    public Boolean valueExists(String columnName, String tableName, int value) throws SQLException {
        ResultSet rs = statement.executeQuery("SELECT COUNT( " + columnName + " ) FROM " + tableName + " WHERE " + columnName + " = " + valueQueryPrep(value));
        int temp = 0;
        while (rs.next()) {
            temp++;
        }
        return temp > 0;
    }

    /**
     * searches a table for a STRING value and returns true/false based on the given arguments below:
     *
     * @param columnName - name of column
     * @param tableName  - name of table
     * @param value      - the value being searched by the user
     * @return
     * @throws SQLException
     */

    public Boolean valueExists(String columnName, String tableName, String value) throws SQLException {
        ResultSet rs = statement.executeQuery("SELECT COUNT( " + columnName + " ) FROM " + tableName + " WHERE " + columnName + " = " + valueQueryPrep(value));
        int temp = 0;
        while (rs.next()) {
            temp++;
        }
        return temp > 0;
    }

    /**
     * formats and parses the date and time based on the format year-month-day hour:minute:second
     *
     * @param value - the date time value given from the user
     * @return return the parsed time/date format
     */

    public String valueQueryPrep(LocalDateTime value) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");
        String staging = value.format(formatter);
        String result = "'" + staging + "'";
        return result;
    }

    /**
     * formats and parses the date based on the format year-month-day
     *
     * @param value - user provided value
     * @return return the parsed date format
     */

    public String valueQueryPrep(Date value) {
        DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
        String staging = dateFormat.format(value);
        String result = "'" + staging + "'";
        return result;
    }

    /**
     * wrapper class for a millisecond value that is recognized as SQL format
     *
     * @param value - SQL provided value
     * @return returns the proper year-month-day format
     */

    public String valueQueryPrep(java.sql.Date value) {
        DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
        String staging = dateFormat.format(value);
        String result = "'" + staging + "'";
        return result;
    }

    /**
     * quote wraps a decimal value
     *
     * @param value - user provided decimal value
     * @return return a quote wrapped string
     */

    public String valueQueryPrep(double value) {
        String result = "'" + value + "'";
        return result;
    }

    /**
     * quote wraps a integer value
     *
     * @param value - user provided integer value
     * @return return a quote wrapped string
     */

    public String valueQueryPrep(int value) {
        String result = "'" + value + "'";
        return result;
    }

    /**
     * quote wraps a integer value
     *
     * @param value - user provided String value
     * @return return a quote wrapped string
     */

    public static String valueQueryPrep(String value) {
        value = "'" + value + "'";
        return value;
    }
}
